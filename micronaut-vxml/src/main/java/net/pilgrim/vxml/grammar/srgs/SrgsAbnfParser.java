package net.pilgrim.vxml.grammar.srgs;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for W3C SRGS 1.0 ABNF format (#ABNF 1.0).
 */
public class SrgsAbnfParser {

    private static final Pattern HEADER_PATTERN = Pattern.compile("^#(?:ABNF|abnf)\\s+([0-9\\.]+)(?:\\s+([^;]+))?;");
    private static final Pattern DIRECTIVE_PATTERN = Pattern.compile("^(language|mode|root|tag-format)\\s+([^;]+);", Pattern.CASE_INSENSITIVE);

    public SrgsGrammar parse(String abnfContent) {
        if (abnfContent == null || abnfContent.isBlank()) {
            throw new IllegalArgumentException("ABNF grammar content cannot be empty");
        }

        String mode = "voice";
        String rootRuleId = null;
        String xmlLang = "en-US";
        String version = "1.0";
        String tagFormat = null;
        Map<String, SrgsRule> rules = new LinkedHashMap<>();

        // Remove comments (lines starting with //)
        StringBuilder cleanContent = new StringBuilder();
        for (String line : abnfContent.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("//")) {
                cleanContent.append(line).append(" ");
            }
        }

        String content = cleanContent.toString().trim();

        // Check header
        Matcher hm = HEADER_PATTERN.matcher(content);
        if (hm.find()) {
            version = hm.group(1);
            content = content.substring(hm.end()).trim();
        }

        // Process directives
        while (true) {
            Matcher dm = DIRECTIVE_PATTERN.matcher(content);
            if (dm.find()) {
                String dirName = dm.group(1).toLowerCase(Locale.ROOT);
                String dirVal = dm.group(2).trim();
                switch (dirName) {
                    case "language" -> xmlLang = dirVal;
                    case "mode" -> mode = dirVal.toLowerCase(Locale.ROOT);
                    case "root" -> rootRuleId = dirVal.startsWith("$") ? dirVal.substring(1) : dirVal;
                    case "tag-format" -> tagFormat = dirVal;
                }
                content = content.substring(dm.end()).trim();
            } else {
                break;
            }
        }

        // Parse rules: $ruleName = expansion; or $public $ruleName = expansion;
        for (String ruleDef : splitStatements(content)) {
            String trimmedRule = ruleDef.trim();
            if (trimmedRule.isEmpty()) continue;

            int eqIdx = trimmedRule.indexOf('=');
            if (eqIdx == -1) continue;

            String lhs = trimmedRule.substring(0, eqIdx).trim();
            String rhs = trimmedRule.substring(eqIdx + 1).trim();

            boolean isPublic = false;
            if (lhs.toLowerCase(Locale.ROOT).startsWith("$public")) {
                isPublic = true;
                lhs = lhs.substring(7).trim();
            }
            if (lhs.startsWith("$")) {
                lhs = lhs.substring(1).trim();
            }

            String ruleId = lhs;
            SrgsNode expansion = parseExpansion(rhs);
            SrgsRule rule = new SrgsRule(ruleId, isPublic, expansion);
            rules.put(ruleId, rule);

            if (rootRuleId == null && isPublic) {
                rootRuleId = ruleId;
            }
        }

        if (rootRuleId == null && !rules.isEmpty()) {
            rootRuleId = rules.keySet().iterator().next();
        }

        return new SrgsGrammar(mode, rootRuleId, xmlLang, version, tagFormat, rules);
    }

    private List<String> splitStatements(String content) {
        List<String> statements = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int braceDepth = 0;
        boolean inQuote = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                cur.append(c);
            } else if (!inQuote) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
                else if (c == ';' && braceDepth == 0) {
                    if (!cur.toString().isBlank()) {
                        statements.add(cur.toString().trim());
                    }
                    cur.setLength(0);
                    continue;
                }
                cur.append(c);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) {
            statements.add(cur.toString().trim());
        }
        return statements;
    }

    private SrgsNode parseExpansion(String expr) {
        String trimmed = expr.trim();
        if (trimmed.isEmpty()) return null;

        // Alternatives split by | outside quotes/brackets
        List<String> alternatives = splitTopLevel(trimmed, '|');
        if (alternatives.size() > 1) {
            List<SrgsNode> options = new ArrayList<>();
            for (String alt : alternatives) {
                SrgsNode n = parseSequence(alt);
                if (n != null) options.add(n);
            }
            return new SrgsOneOf(options);
        }

        return parseSequence(trimmed);
    }

    private SrgsNode parseSequence(String seq) {
        List<String> tokens = tokenizeSequence(seq.trim());
        List<SrgsNode> nodes = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("{") && token.endsWith("}")) {
                // Semantic tag
                String tagContent = token.substring(1, token.length() - 1).trim();
                // Attach tag to previous item if possible
                if (!nodes.isEmpty()) {
                    SrgsNode last = nodes.get(nodes.size() - 1);
                    if (last instanceof SrgsItem it && it.getTag() == null) {
                        nodes.set(nodes.size() - 1, new SrgsItem(it.getChild(), it.getMinRepeat(), it.getMaxRepeat(), it.getWeight(), tagContent));
                        continue;
                    } else {
                        nodes.set(nodes.size() - 1, new SrgsItem(last, 1, 1, 1.0f, tagContent));
                        continue;
                    }
                }
                nodes.add(new SrgsTag(tagContent));
            } else if (token.startsWith("[") && token.endsWith("]")) {
                // Optional expansion: repeat 0-1
                String inner = token.substring(1, token.length() - 1).trim();
                SrgsNode child = parseExpansion(inner);
                nodes.add(new SrgsItem(child, 0, 1, 1.0f, null));
            } else if (token.startsWith("(") && token.endsWith(")")) {
                // Grouping
                String inner = token.substring(1, token.length() - 1).trim();
                nodes.add(parseExpansion(inner));
            } else if (token.startsWith("$")) {
                // Rule reference
                nodes.add(SrgsRuleRef.toRule(token.substring(1)));
            } else {
                // Literal token or quoted string
                String clean = token;
                if ((clean.startsWith("\"") && clean.endsWith("\"")) || (clean.startsWith("'") && clean.endsWith("'"))) {
                    clean = clean.substring(1, clean.length() - 1);
                }
                nodes.add(new SrgsToken(clean));
            }
        }

        if (nodes.isEmpty()) return null;
        if (nodes.size() == 1) return nodes.get(0);
        return new SrgsSequence(nodes);
    }

    private List<String> splitTopLevel(String input, char delimiter) {
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                cur.append(c);
            } else if (!inQuote) {
                if (c == '(') parenDepth++;
                else if (c == ')') parenDepth--;
                else if (c == '[') bracketDepth++;
                else if (c == ']') bracketDepth--;
                else if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
                else if (c == delimiter && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    result.add(cur.toString().trim());
                    cur.setLength(0);
                    continue;
                }
                cur.append(c);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            result.add(cur.toString().trim());
        }
        return result;
    }

    private List<String> tokenizeSequence(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean inQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                cur.append(c);
            } else if (!inQuote) {
                if (c == '(') parenDepth++;
                else if (c == ')') parenDepth--;
                else if (c == '[') bracketDepth++;
                else if (c == ']') bracketDepth--;
                else if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;

                if (Character.isWhitespace(c) && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    if (cur.length() > 0) {
                        tokens.add(cur.toString().trim());
                        cur.setLength(0);
                    }
                    continue;
                }
                cur.append(c);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            tokens.add(cur.toString().trim());
        }
        return tokens;
    }
}
