package net.pilgrim.sip.router;

import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipUri;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pre-compiled pattern matcher for SIP Request-URI and username routing.
 * <p>
 * Supports:
 * <ul>
 *   <li>Exact matches: {@code "ai"}, {@code "annc"}, {@code "mailbox"}</li>
 *   <li>URI template variables: {@code "users/{id}"}, {@code "room/{roomId:[0-9]+}"}</li>
 *   <li>Explicit regex: {@code "regex:^sip:(?<ext>[0-9]{3,4})@.*"}, {@code "regex:(?<svc>support|sales)"}</li>
 *   <li>Prefix matching: {@code "conf-"}</li>
 *   <li>Catch-all: {@code ""}</li>
 * </ul>
 * Zero reflection: regex patterns and variable extractors are compiled Ahead-of-Time.
 */
public final class SipPatternMatcher implements Comparable<SipPatternMatcher> {

    private static final Pattern TEMPLATE_VAR_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)(?::([^}]+))?\\}");
    private static final Pattern NAMED_GROUP_PATTERN = Pattern.compile("\\(\\?<([a-zA-Z0-9_]+)>");

    private final String rawPattern;
    private final Pattern compiledPattern;
    private final List<String> variableNames;
    private final int precedence;
    private final boolean isRegex;
    private final boolean isExact;

    public SipPatternMatcher(String rawPattern) {
        this.rawPattern = rawPattern != null ? rawPattern.trim() : "";

        if (this.rawPattern.isEmpty()) {
            this.compiledPattern = null;
            this.variableNames = Collections.emptyList();
            this.precedence = 0; // lowest precedence
            this.isRegex = false;
            this.isExact = false;
            return;
        }

        List<String> vars = new ArrayList<>();

        if (this.rawPattern.startsWith("regex:")) {
            // Explicit regex definition
            String regexBody = this.rawPattern.substring(6).trim();
            this.compiledPattern = Pattern.compile(regexBody, Pattern.CASE_INSENSITIVE);
            this.isRegex = true;
            this.isExact = false;
            this.precedence = 500;

            // Extract named group names from regex
            Matcher groupMatcher = NAMED_GROUP_PATTERN.matcher(regexBody);
            while (groupMatcher.find()) {
                vars.add(groupMatcher.group(1));
            }
        } else if (this.rawPattern.contains("{") && this.rawPattern.contains("}")) {
            // URI Template syntax: e.g. users/{id:[0-9]+} or room/{roomId}
            StringBuilder regexBuilder = new StringBuilder();
            Matcher tm = TEMPLATE_VAR_PATTERN.matcher(this.rawPattern);
            int lastEnd = 0;
            while (tm.find()) {
                regexBuilder.append(Pattern.quote(this.rawPattern.substring(lastEnd, tm.start())));
                String varName = tm.group(1);
                String customRegex = tm.group(2);
                vars.add(varName);

                if (customRegex != null && !customRegex.isBlank()) {
                    regexBuilder.append("(?<").append(varName).append(">").append(customRegex).append(")");
                } else {
                    regexBuilder.append("(?<").append(varName).append(">[^/@:;?]+)");
                }
                lastEnd = tm.end();
            }
            regexBuilder.append(Pattern.quote(this.rawPattern.substring(lastEnd)));

            this.compiledPattern = Pattern.compile(regexBuilder.toString(), Pattern.CASE_INSENSITIVE);
            this.isRegex = true;
            this.isExact = false;
            this.precedence = 600;
        } else if (hasRegexMetaCharacters(this.rawPattern)) {
            // Raw regex without 'regex:' prefix (e.g. ^[0-9]+$ or [0-9]{3,4})
            this.compiledPattern = Pattern.compile(this.rawPattern, Pattern.CASE_INSENSITIVE);
            this.isRegex = true;
            this.isExact = false;
            this.precedence = 400;

            Matcher groupMatcher = NAMED_GROUP_PATTERN.matcher(this.rawPattern);
            while (groupMatcher.find()) {
                vars.add(groupMatcher.group(1));
            }
        } else {
            // Exact / Prefix literal
            this.compiledPattern = null;
            this.isRegex = false;
            this.isExact = true;
            this.precedence = 1000; // highest precedence for exact matches
        }

        this.variableNames = Collections.unmodifiableList(vars);
    }

    private static boolean hasRegexMetaCharacters(String s) {
        return s.contains("^") || s.contains("$") || s.contains("*") || s.contains("+")
                || s.contains("?") || s.contains("(") || s.contains("[") || s.contains("|")
                || s.contains("\\");
    }

    /**
     * Attempts to match the SIP request against this pattern, extracting path variables if matched.
     *
     * @param request incoming SIP request
     * @return empty Optional if no match; Optional with variable Map (can be empty) if matched
     */
    public Optional<Map<String, String>> match(SipRequest request) {
        if (request == null) {
            return Optional.empty();
        }

        if (rawPattern.isEmpty()) {
            return Optional.of(Collections.emptyMap());
        }

        SipUri uri = request.getUri();
        String user = (uri != null && uri.getUser() != null) ? uri.getUser() : "";
        String fullUri = (uri != null) ? uri.toString() : "";

        if (isExact) {
            // Exact match on username or URI substring/prefix
            if (user.equalsIgnoreCase(rawPattern)
                    || fullUri.equalsIgnoreCase(rawPattern)
                    || user.toLowerCase(Locale.ROOT).startsWith(rawPattern.toLowerCase(Locale.ROOT))
                    || fullUri.toLowerCase(Locale.ROOT).contains(rawPattern.toLowerCase(Locale.ROOT))) {
                return Optional.of(Collections.emptyMap());
            }
            return Optional.empty();
        }

        if (compiledPattern != null) {
            // Test user first
            Matcher userMatcher = compiledPattern.matcher(user);
            if (userMatcher.matches() || userMatcher.find()) {
                return Optional.of(extractVariables(userMatcher));
            }

            // Test full URI
            Matcher uriMatcher = compiledPattern.matcher(fullUri);
            if (uriMatcher.matches() || uriMatcher.find()) {
                return Optional.of(extractVariables(uriMatcher));
            }
        }

        return Optional.empty();
    }

    private Map<String, String> extractVariables(Matcher matcher) {
        if (variableNames.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> vars = new LinkedHashMap<>();
        for (String varName : variableNames) {
            try {
                String val = matcher.group(varName);
                if (val != null) {
                    vars.put(varName, val);
                }
            } catch (IllegalArgumentException ignored) {
                // Not a valid named group in this pattern
            }
        }
        return vars;
    }

    public String getRawPattern() {
        return rawPattern;
    }

    public Pattern getCompiledPattern() {
        return compiledPattern;
    }

    public List<String> getVariableNames() {
        return variableNames;
    }

    public int getPrecedence() {
        return precedence;
    }

    public boolean isRegex() {
        return isRegex;
    }

    public boolean isExact() {
        return isExact;
    }

    @Override
    public int compareTo(SipPatternMatcher o) {
        // Higher precedence comes first
        int cmp = Integer.compare(o.precedence, this.precedence);
        if (cmp != 0) return cmp;
        return Integer.compare(o.rawPattern.length(), this.rawPattern.length());
    }

    @Override
    public String toString() {
        return "SipPatternMatcher{" +
                "pattern='" + rawPattern + '\'' +
                ", precedence=" + precedence +
                ", vars=" + variableNames +
                '}';
    }
}
