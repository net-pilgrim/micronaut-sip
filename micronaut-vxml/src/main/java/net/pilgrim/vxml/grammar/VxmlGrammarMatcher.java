package net.pilgrim.vxml.grammar;

import net.pilgrim.vxml.ast.VxmlChoice;
import net.pilgrim.vxml.ast.VxmlField;
import net.pilgrim.vxml.ast.VxmlGrammar;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches DTMF input buffers against active VoiceXML field grammars, choices,
 * and built-in types (dtmf/digits, dtmf/boolean).
 */
public class VxmlGrammarMatcher {

    private static final Pattern SRGS_ITEM_TAG_PATTERN = Pattern.compile("<item>([^<]+)(?:<tag>([^<]+)</tag>)?</item>");

    public GrammarMatchResult match(VxmlField field, String dtmfBuffer) {
        if (field == null || dtmfBuffer == null || dtmfBuffer.isEmpty()) {
            return GrammarMatchResult.incomplete();
        }

        // 1. Check <choice> elements
        if (!field.getChoices().isEmpty()) {
            boolean hasPrefixMatch = false;
            for (VxmlChoice choice : field.getChoices()) {
                String choiceDtmf = choice.getDtmf();
                if (choiceDtmf != null) {
                    if (choiceDtmf.equals(dtmfBuffer)) {
                        return GrammarMatchResult.matchChoice(dtmfBuffer, choice.getNext());
                    }
                    if (choiceDtmf.startsWith(dtmfBuffer)) {
                        hasPrefixMatch = true;
                    }
                }
            }
            if (hasPrefixMatch) {
                return GrammarMatchResult.incomplete();
            }
        }

        // 2. Check built-in types via field type attribute
        String type = field.getType();
        if (type != null && !type.isBlank()) {
            GrammarMatchResult typeResult = matchBuiltinType(type.trim(), dtmfBuffer);
            if (typeResult.isMatch() || typeResult.isIncomplete()) {
                return typeResult;
            }
        }

        // 3. Check <grammar> elements
        if (!field.getGrammars().isEmpty()) {
            boolean hasIncomplete = false;
            for (VxmlGrammar grammar : field.getGrammars()) {
                GrammarMatchResult gramResult = matchGrammar(grammar, dtmfBuffer);
                if (gramResult.isMatch()) {
                    return gramResult;
                }
                if (gramResult.isIncomplete()) {
                    hasIncomplete = true;
                }
            }
            if (hasIncomplete) {
                return GrammarMatchResult.incomplete();
            }
        }

        // 4. Default: if no grammars or choices defined, single digit matches directly
        if (field.getChoices().isEmpty() && (field.getType() == null || field.getType().isBlank()) && field.getGrammars().isEmpty()) {
            return GrammarMatchResult.match(dtmfBuffer);
        }

        return GrammarMatchResult.noMatch();
    }

    private GrammarMatchResult matchBuiltinType(String typeSpec, String dtmfBuffer) {
        String baseType = typeSpec;
        Map<String, String> params = new HashMap<>();

        int qIdx = typeSpec.indexOf('?');
        if (qIdx != -1) {
            baseType = typeSpec.substring(0, qIdx).trim();
            String query = typeSpec.substring(qIdx + 1);
            for (String param : query.split("[;&]")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) {
                    params.put(kv[0].trim().toLowerCase(), kv[1].trim());
                }
            }
        }

        if (baseType.equalsIgnoreCase("boolean") || baseType.equalsIgnoreCase("builtin:dtmf/boolean")) {
            if ("1".equals(dtmfBuffer)) {
                return GrammarMatchResult.match("true");
            }
            if ("2".equals(dtmfBuffer)) {
                return GrammarMatchResult.match("false");
            }
            return GrammarMatchResult.noMatch();
        }

        if (baseType.equalsIgnoreCase("digits") || baseType.equalsIgnoreCase("builtin:dtmf/digits")) {
            int length = -1;
            int minLength = 1;
            int maxLength = 32;

            if (params.containsKey("length")) {
                try {
                    length = Integer.parseInt(params.get("length"));
                } catch (NumberFormatException ignored) {}
            }
            if (params.containsKey("minlength")) {
                try {
                    minLength = Integer.parseInt(params.get("minlength"));
                } catch (NumberFormatException ignored) {}
            }
            if (params.containsKey("maxlength")) {
                try {
                    maxLength = Integer.parseInt(params.get("maxlength"));
                } catch (NumberFormatException ignored) {}
            }

            if (length > 0) {
                if (dtmfBuffer.length() < length) {
                    return GrammarMatchResult.incomplete();
                }
                if (dtmfBuffer.length() == length) {
                    return GrammarMatchResult.match(dtmfBuffer);
                }
                return GrammarMatchResult.noMatch();
            }

            // Handling with '#' termination
            if (dtmfBuffer.endsWith("#")) {
                String cleanDigits = dtmfBuffer.substring(0, dtmfBuffer.length() - 1);
                if (cleanDigits.length() >= minLength && cleanDigits.length() <= maxLength) {
                    return GrammarMatchResult.match(cleanDigits);
                }
                return GrammarMatchResult.noMatch();
            }

            if (dtmfBuffer.length() < maxLength) {
                return GrammarMatchResult.incomplete();
            }
            if (dtmfBuffer.length() == maxLength) {
                return GrammarMatchResult.match(dtmfBuffer);
            }
        }

        return GrammarMatchResult.noMatch();
    }

    private GrammarMatchResult matchGrammar(VxmlGrammar grammar, String dtmfBuffer) {
        String content = grammar.getInlineContent();
        if (content == null || content.isBlank()) {
            return GrammarMatchResult.noMatch();
        }

        // Simple pipe-delimited list: e.g. "1 | 2 | 3"
        if (content.contains("|")) {
            boolean hasPrefix = false;
            for (String part : content.split("\\|")) {
                String expected = part.trim();
                if (expected.equals(dtmfBuffer)) {
                    return GrammarMatchResult.match(dtmfBuffer);
                }
                if (expected.startsWith(dtmfBuffer)) {
                    hasPrefix = true;
                }
            }
            if (hasPrefix) {
                return GrammarMatchResult.incomplete();
            }
            return GrammarMatchResult.noMatch();
        }

        // SRGS XML subset: <item>1<tag>sales</tag></item>
        if (content.contains("<item>")) {
            Matcher matcher = SRGS_ITEM_TAG_PATTERN.matcher(content);
            boolean hasPrefix = false;
            while (matcher.find()) {
                String itemText = matcher.group(1).trim();
                String tag = matcher.group(2);
                if (itemText.equals(dtmfBuffer)) {
                    return GrammarMatchResult.match(tag != null && !tag.isBlank() ? tag.trim() : itemText);
                }
                if (itemText.startsWith(dtmfBuffer)) {
                    hasPrefix = true;
                }
            }
            if (hasPrefix) {
                return GrammarMatchResult.incomplete();
            }
            return GrammarMatchResult.noMatch();
        }

        // Plain string/regex match
        if (content.trim().equals(dtmfBuffer)) {
            return GrammarMatchResult.match(dtmfBuffer);
        }
        if (content.trim().startsWith(dtmfBuffer)) {
            return GrammarMatchResult.incomplete();
        }

        return GrammarMatchResult.noMatch();
    }
}
