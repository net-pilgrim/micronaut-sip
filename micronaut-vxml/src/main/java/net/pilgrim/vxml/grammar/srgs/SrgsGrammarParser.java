package net.pilgrim.vxml.grammar.srgs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified parser for SRGS grammars, supporting GrXML (XML) and ABNF formats,
 * as well as shorthand pipe-separated lists.
 */
public class SrgsGrammarParser {

    private final SrgsXmlParser xmlParser = new SrgsXmlParser();
    private final SrgsAbnfParser abnfParser = new SrgsAbnfParser();

    /**
     * Parses the grammar content into an {@link SrgsGrammar} instance.
     *
     * @param content raw grammar text
     * @param defaultMode fallback mode ("voice" or "dtmf")
     * @return parsed SrgsGrammar
     */
    public SrgsGrammar parse(String content, String defaultMode) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Grammar content cannot be empty");
        }

        String trimmed = content.trim();

        // 1. Check for XML format
        if (trimmed.startsWith("<") || trimmed.contains("<grammar") || trimmed.contains("<rule") || trimmed.contains("<one-of>")) {
            try {
                return xmlParser.parse(trimmed);
            } catch (Exception e) {
                // If it looks like XML but fails, propagate error
                throw new RuntimeException("Failed to parse SRGS XML grammar: " + e.getMessage(), e);
            }
        }

        // 2. Check for ABNF format
        if (trimmed.startsWith("#ABNF") || trimmed.startsWith("#abnf") || trimmed.contains("root $") || trimmed.contains("$")) {
            return abnfParser.parse(trimmed);
        }

        // 3. Fallback: simple pipe-delimited list (e.g. "sales | billing | support" or "1 | 2 | 3")
        List<SrgsNode> options = new ArrayList<>();
        for (String item : trimmed.split("\\|")) {
            String token = item.trim();
            if (!token.isEmpty()) {
                options.add(new SrgsItem(new SrgsToken(token)));
            }
        }
        SrgsOneOf oneOf = new SrgsOneOf(options);
        SrgsRule defaultRule = new SrgsRule("default", true, oneOf);
        Map<String, SrgsRule> rules = new LinkedHashMap<>();
        rules.put("default", defaultRule);

        String mode = defaultMode != null ? defaultMode : "dtmf";
        return new SrgsGrammar(mode, "default", "en-US", "1.0", null, rules);
    }
}
