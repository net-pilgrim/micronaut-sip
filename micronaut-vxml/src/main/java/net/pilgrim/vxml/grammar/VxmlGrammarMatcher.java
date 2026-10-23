package net.pilgrim.vxml.grammar;

import net.pilgrim.vxml.ast.VxmlChoice;
import net.pilgrim.vxml.ast.VxmlField;
import net.pilgrim.vxml.ast.VxmlGrammar;
import net.pilgrim.vxml.grammar.srgs.SrgsGrammar;
import net.pilgrim.vxml.grammar.srgs.SrgsGrammarParser;
import net.pilgrim.vxml.grammar.srgs.SrgsMatcher;
import net.pilgrim.vxml.loader.DefaultVxmlAudioLoader;
import net.pilgrim.vxml.loader.VxmlAudioLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches DTMF input buffers and spoken speech utterances against active VoiceXML
 * field grammars, choices, built-in types (digits, boolean), and W3C SRGS grammars.
 */
public class VxmlGrammarMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(VxmlGrammarMatcher.class);

    private static final Pattern SRGS_ITEM_TAG_PATTERN = Pattern.compile("<item>([^<]+)(?:<tag>([^<]+)</tag>)?</item>");
    private static final Map<String, String> WORD_TO_DIGIT = new HashMap<>();

    static {
        WORD_TO_DIGIT.put("zero", "0");
        WORD_TO_DIGIT.put("oh", "0");
        WORD_TO_DIGIT.put("one", "1");
        WORD_TO_DIGIT.put("two", "2");
        WORD_TO_DIGIT.put("three", "3");
        WORD_TO_DIGIT.put("four", "4");
        WORD_TO_DIGIT.put("five", "5");
        WORD_TO_DIGIT.put("six", "6");
        WORD_TO_DIGIT.put("seven", "7");
        WORD_TO_DIGIT.put("eight", "8");
        WORD_TO_DIGIT.put("nine", "9");
    }

    private final SrgsGrammarParser srgsParser = new SrgsGrammarParser();
    private final SrgsMatcher srgsMatcher = new SrgsMatcher();
    private final VxmlAudioLoader audioLoader;

    public VxmlGrammarMatcher() {
        this(new DefaultVxmlAudioLoader());
    }

    public VxmlGrammarMatcher(VxmlAudioLoader audioLoader) {
        this.audioLoader = audioLoader != null ? audioLoader : new DefaultVxmlAudioLoader();
    }

    // ==========================================
    // DTMF Matching
    // ==========================================

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
                if ("voice".equalsIgnoreCase(grammar.getMode())) {
                    continue;
                }
                GrammarMatchResult gramResult = matchGrammarDtmf(grammar, dtmfBuffer);
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

    // ==========================================
    // Voice / Utterance Matching
    // ==========================================

    /**
     * Matches a spoken utterance string against the active VoiceXML field grammars, choices,
     * and built-in types.
     *
     * @param field the active field
     * @param utterance the recognized speech utterance
     * @return match result
     */
    public GrammarMatchResult matchVoice(VxmlField field, String utterance) {
        if (field == null || utterance == null || utterance.isBlank()) {
            return GrammarMatchResult.noMatch();
        }

        String cleanUtterance = utterance.trim();

        // 1. Check <choice> elements for spoken matches
        if (!field.getChoices().isEmpty()) {
            for (VxmlChoice choice : field.getChoices()) {
                String choiceText = choice.getText();
                if (choiceText != null && !choiceText.isBlank()) {
                    if (choiceText.equalsIgnoreCase(cleanUtterance)) {
                        return GrammarMatchResult.matchChoice(choiceText, choice.getNext());
                    }
                }
            }
        }

        // 2. Check built-in types for spoken voice
        String type = field.getType();
        if (type != null && !type.isBlank()) {
            GrammarMatchResult typeResult = matchBuiltinVoiceType(type.trim(), cleanUtterance);
            if (typeResult.isMatch()) {
                return typeResult;
            }
        }

        // 3. Check <grammar> elements in voice mode
        if (!field.getGrammars().isEmpty()) {
            for (VxmlGrammar grammar : field.getGrammars()) {
                // Skip if grammar is strictly DTMF mode
                if ("dtmf".equalsIgnoreCase(grammar.getMode())) {
                    continue;
                }
                GrammarMatchResult gramResult = matchGrammarVoice(grammar, cleanUtterance);
                if (gramResult.isMatch()) {
                    return gramResult;
                }
            }
        }

        // 4. Default: if no grammars or choices defined, utterance matches directly
        if (field.getChoices().isEmpty() && (field.getType() == null || field.getType().isBlank()) && field.getGrammars().isEmpty()) {
            return GrammarMatchResult.match(cleanUtterance);
        }

        return GrammarMatchResult.noMatch();
    }

    // ==========================================
    // Internal Helper Methods
    // ==========================================

    private GrammarMatchResult matchBuiltinVoiceType(String typeSpec, String utterance) {
        String baseType = extractBaseType(typeSpec);
        String lower = utterance.toLowerCase(Locale.ROOT).trim();

        if (baseType.equalsIgnoreCase("boolean") || baseType.equalsIgnoreCase("builtin:grammar/boolean")) {
            if (lower.equals("yes") || lower.equals("true") || lower.equals("correct") || lower.equals("yeah") || lower.equals("yep") || lower.equals("1")) {
                return GrammarMatchResult.match("true");
            }
            if (lower.equals("no") || lower.equals("false") || lower.equals("incorrect") || lower.equals("nope") || lower.equals("2")) {
                return GrammarMatchResult.match("false");
            }
            return GrammarMatchResult.noMatch();
        }

        if (baseType.equalsIgnoreCase("digits") || baseType.equalsIgnoreCase("builtin:grammar/digits")) {
            String digits = convertSpokenWordsToDigits(lower);
            if (digits != null && !digits.isEmpty()) {
                return GrammarMatchResult.match(digits);
            }
        }

        return GrammarMatchResult.noMatch();
    }

    private String convertSpokenWordsToDigits(String text) {
        StringBuilder sb = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (WORD_TO_DIGIT.containsKey(word)) {
                sb.append(WORD_TO_DIGIT.get(word));
            } else if (word.matches("\\d+")) {
                sb.append(word);
            } else {
                return null;
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private GrammarMatchResult matchBuiltinType(String typeSpec, String dtmfBuffer) {
        String baseType = extractBaseType(typeSpec);
        Map<String, String> params = extractParams(typeSpec);

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

    private GrammarMatchResult matchGrammarDtmf(VxmlGrammar grammar, String dtmfBuffer) {
        String content = resolveGrammarContent(grammar);
        if (content == null || content.isBlank()) {
            return GrammarMatchResult.noMatch();
        }

        // Try SRGS grammar engine first
        try {
            SrgsGrammar srgs = srgsParser.parse(content, "dtmf");
            GrammarMatchResult res = srgsMatcher.match(srgs, dtmfBuffer, false);
            if (res.isMatch() || res.isIncomplete()) {
                return res;
            }
        } catch (Exception e) {
            LOG.debug("SRGS parse/match skipped: {}", e.getMessage());
        }

        // Fallback: simple pipe-delimited list: e.g. "1 | 2 | 3"
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

        // Fallback: SRGS XML subset regex: <item>1<tag>sales</tag></item>
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

        // Plain string match
        if (content.trim().equals(dtmfBuffer)) {
            return GrammarMatchResult.match(dtmfBuffer);
        }
        if (content.trim().startsWith(dtmfBuffer)) {
            return GrammarMatchResult.incomplete();
        }

        return GrammarMatchResult.noMatch();
    }

    private GrammarMatchResult matchGrammarVoice(VxmlGrammar grammar, String utterance) {
        String content = resolveGrammarContent(grammar);
        if (content == null || content.isBlank()) {
            return GrammarMatchResult.noMatch();
        }

        // Try SRGS grammar engine
        try {
            SrgsGrammar srgs = srgsParser.parse(content, "voice");
            GrammarMatchResult res = srgsMatcher.match(srgs, utterance, true);
            if (res.isMatch()) {
                return res;
            }
        } catch (Exception e) {
            LOG.debug("SRGS voice parse/match skipped: {}", e.getMessage());
        }

        // Fallback: simple pipe-delimited list for voice (e.g. "sales | billing | support")
        if (content.contains("|")) {
            for (String part : content.split("\\|")) {
                if (part.trim().equalsIgnoreCase(utterance)) {
                    return GrammarMatchResult.match(utterance);
                }
            }
        }

        if (content.trim().equalsIgnoreCase(utterance)) {
            return GrammarMatchResult.match(utterance);
        }

        return GrammarMatchResult.noMatch();
    }

    private String resolveGrammarContent(VxmlGrammar grammar) {
        if (grammar == null) return null;
        if (grammar.getInlineContent() != null && !grammar.getInlineContent().isBlank()) {
            return grammar.getInlineContent();
        }
        if (grammar.getSrc() != null && !grammar.getSrc().isBlank() && audioLoader != null) {
            try {
                byte[] bytes = audioLoader.loadAudio(grammar.getSrc());
                if (bytes != null && bytes.length > 0) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                LOG.warn("Failed to load external grammar from: {}", grammar.getSrc(), e);
            }
        }
        return null;
    }

    private static String extractBaseType(String typeSpec) {
        int qIdx = typeSpec.indexOf('?');
        return qIdx != -1 ? typeSpec.substring(0, qIdx).trim() : typeSpec.trim();
    }

    private static Map<String, String> extractParams(String typeSpec) {
        Map<String, String> params = new HashMap<>();
        int qIdx = typeSpec.indexOf('?');
        if (qIdx != -1) {
            String query = typeSpec.substring(qIdx + 1);
            for (String param : query.split("[;&]")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) {
                    params.put(kv[0].trim().toLowerCase(Locale.ROOT), kv[1].trim());
                }
            }
        }
        return params;
    }
}
