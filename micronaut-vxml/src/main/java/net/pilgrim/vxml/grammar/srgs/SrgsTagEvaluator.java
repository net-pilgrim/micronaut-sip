package net.pilgrim.vxml.grammar.srgs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates W3C Semantic Interpretation for Speech Recognition (SISR) tags
 * from SRGS grammars.
 */
public final class SrgsTagEvaluator {

    private static final Pattern OUT_ASSIGN_PATTERN =
            Pattern.compile("(?:out|\\$)(?:\\.([a-zA-Z0-9_]+))?\\s*=\\s*(?:(?:['\"]([^'\"]*)['\"])|([a-zA-Z0-9_\\.-]+));?");

    private SrgsTagEvaluator() {}

    /**
     * Evaluates a tag string into a semantic result value.
     *
     * @param rawTag the raw text inside &lt;tag&gt; or {...}
     * @param defaultText the matched literal text fallback if tag cannot be parsed
     * @return semantic value string
     */
    public static String evaluate(String rawTag, String defaultText) {
        if (rawTag == null || rawTag.isBlank()) {
            return defaultText;
        }

        String trimmed = rawTag.trim();
        // Remove trailing semicolon if present
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }

        // If it's a simple literal without '=' (e.g. "sales" or 'sales' or just sales)
        if (!trimmed.contains("=")) {
            if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
            return trimmed;
        }

        // Check for assignments (e.g. out="sales", out.dest="sales", $="yes", out=true)
        Matcher m = OUT_ASSIGN_PATTERN.matcher(rawTag);
        Map<String, String> properties = new LinkedHashMap<>();
        String rootValue = null;

        while (m.find()) {
            String propName = m.group(1);
            String quotedVal = m.group(2);
            String unquotedVal = m.group(3);
            String val = quotedVal != null ? quotedVal : unquotedVal;

            if (propName != null && !propName.isBlank()) {
                properties.put(propName, val);
            } else {
                rootValue = val;
            }
        }

        if (properties.isEmpty()) {
            return rootValue != null ? rootValue : trimmed;
        }

        if (properties.size() == 1 && rootValue == null) {
            return properties.values().iterator().next();
        }

        // Format as simple JSON object if multiple properties are set
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (rootValue != null) {
            sb.append("\"value\":\"").append(escapeJson(rootValue)).append("\"");
            first = false;
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
              .append(escapeJson(entry.getValue())).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
