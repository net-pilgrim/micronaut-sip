package net.pilgrim.vxml.grammar.srgs;

import net.pilgrim.vxml.grammar.GrammarMatchResult;

import java.util.*;

/**
 * Matching engine that matches DTMF digit buffers and spoken speech utterances
 * against W3C SRGS grammars.
 */
public class SrgsMatcher {

    private static final int MAX_RECURSION_DEPTH = 32;

    /**
     * Matches input against an SRGS grammar in the given mode.
     *
     * @param grammar the SRGS grammar
     * @param input speech utterance or DTMF digits
     * @param isVoiceMode true if voice matching, false if DTMF matching
     * @return match result
     */
    public GrammarMatchResult match(SrgsGrammar grammar, String input, boolean isVoiceMode) {
        if (grammar == null || input == null || input.isBlank()) {
            return isVoiceMode ? GrammarMatchResult.noMatch() : GrammarMatchResult.incomplete();
        }

        SrgsRule rootRule = grammar.getRootRule();
        if (rootRule == null || rootRule.getNode() == null) {
            return GrammarMatchResult.noMatch();
        }

        if (isVoiceMode) {
            return matchVoice(grammar, rootRule, input);
        } else {
            return matchDtmf(grammar, rootRule, input);
        }
    }

    // ==========================================
    // Voice Mode Matching
    // ==========================================

    private GrammarMatchResult matchVoice(SrgsGrammar grammar, SrgsRule rootRule, String utterance) {
        List<String> tokens = tokenizeUtterance(utterance);
        if (tokens.isEmpty()) {
            return GrammarMatchResult.noMatch();
        }

        MatchContext ctx = new MatchContext(grammar, tokens, true);
        List<String> capturedTags = new ArrayList<>();
        int nextIndex = matchNode(rootRule.getNode(), 0, capturedTags, ctx, 0);

        if (nextIndex == tokens.size()) {
            String semanticValue = resolveSemanticValue(capturedTags, utterance.trim());
            return GrammarMatchResult.match(semanticValue);
        }

        return GrammarMatchResult.noMatch();
    }

    // ==========================================
    // DTMF Mode Matching
    // ==========================================

    private GrammarMatchResult matchDtmf(SrgsGrammar grammar, SrgsRule rootRule, String dtmfBuffer) {
        String cleanDtmf = dtmfBuffer.trim();
        List<String> digits = new ArrayList<>(cleanDtmf.length());
        for (int i = 0; i < cleanDtmf.length(); i++) {
            digits.add(String.valueOf(cleanDtmf.charAt(i)));
        }

        // Check for exact full match first
        MatchContext ctxExact = new MatchContext(grammar, digits, false);
        List<String> capturedTags = new ArrayList<>();
        int nextIndex = matchNode(rootRule.getNode(), 0, capturedTags, ctxExact, 0);

        if (nextIndex == digits.size()) {
            String semanticValue = resolveSemanticValue(capturedTags, cleanDtmf);
            return GrammarMatchResult.match(semanticValue);
        }

        // Check if current DTMF input is a valid prefix of any possible grammar expansion
        boolean hasPrefix = isDtmfPrefix(rootRule.getNode(), grammar, cleanDtmf);
        if (hasPrefix) {
            return GrammarMatchResult.incomplete();
        }

        return GrammarMatchResult.noMatch();
    }

    private boolean isDtmfPrefix(SrgsNode node, SrgsGrammar grammar, String inputPrefix) {
        List<String> allExpansions = collectAllDtmfExpansions(node, grammar, 0);
        for (String exp : allExpansions) {
            if (exp.startsWith(inputPrefix) && exp.length() > inputPrefix.length()) {
                return true;
            }
        }
        return false;
    }

    private List<String> collectAllDtmfExpansions(SrgsNode node, SrgsGrammar grammar, int depth) {
        if (node == null || depth > 10) return Collections.emptyList();

        if (node instanceof SrgsToken token) {
            return List.of(token.getText().replaceAll("\\s+", ""));
        } else if (node instanceof SrgsItem item) {
            return collectAllDtmfExpansions(item.getChild(), grammar, depth + 1);
        } else if (node instanceof SrgsOneOf oneOf) {
            List<String> results = new ArrayList<>();
            for (SrgsNode child : oneOf.getItems()) {
                results.addAll(collectAllDtmfExpansions(child, grammar, depth + 1));
            }
            return results;
        } else if (node instanceof SrgsSequence seq) {
            List<String> results = List.of("");
            for (SrgsNode child : seq.getNodes()) {
                List<String> childExpansions = collectAllDtmfExpansions(child, grammar, depth + 1);
                if (childExpansions.isEmpty()) continue;
                List<String> combined = new ArrayList<>();
                for (String r : results) {
                    for (String c : childExpansions) {
                        combined.add(r + c);
                    }
                }
                results = combined;
            }
            return results;
        } else if (node instanceof SrgsRuleRef ref) {
            SrgsRule targetRule = grammar.getRule(ref.getRuleId());
            if (targetRule != null) {
                return collectAllDtmfExpansions(targetRule.getNode(), grammar, depth + 1);
            }
        }
        return Collections.emptyList();
    }

    // ==========================================
    // Core Recursive Node Matcher
    // ==========================================

    private int matchNode(SrgsNode node, int tokenIdx, List<String> capturedTags, MatchContext ctx, int depth) {
        if (node == null || depth > MAX_RECURSION_DEPTH) {
            return -1;
        }

        if (node instanceof SrgsToken token) {
            return matchToken(token, tokenIdx, ctx);
        } else if (node instanceof SrgsTag tag) {
            if (tag.getContent() != null && !tag.getContent().isBlank()) {
                capturedTags.add(tag.getContent());
            }
            return tokenIdx;
        } else if (node instanceof SrgsItem item) {
            return matchItem(item, tokenIdx, capturedTags, ctx, depth + 1);
        } else if (node instanceof SrgsOneOf oneOf) {
            return matchOneOf(oneOf, tokenIdx, capturedTags, ctx, depth + 1);
        } else if (node instanceof SrgsSequence seq) {
            return matchSequence(seq, tokenIdx, capturedTags, ctx, depth + 1);
        } else if (node instanceof SrgsRuleRef ref) {
            return matchRuleRef(ref, tokenIdx, capturedTags, ctx, depth + 1);
        }

        return -1;
    }

    private int matchToken(SrgsToken token, int tokenIdx, MatchContext ctx) {
        String expected = token.getText();
        List<String> expectedWords = ctx.isVoiceMode ? tokenizeUtterance(expected) : splitChars(expected);
        if (expectedWords.isEmpty()) {
            return tokenIdx;
        }

        if (tokenIdx + expectedWords.size() > ctx.tokens.size()) {
            return -1;
        }

        for (int i = 0; i < expectedWords.size(); i++) {
            String expWord = expectedWords.get(i);
            String actualWord = ctx.tokens.get(tokenIdx + i);

            if (ctx.isVoiceMode) {
                if (!expWord.equalsIgnoreCase(actualWord)) {
                    return -1;
                }
            } else {
                if (!expWord.equals(actualWord)) {
                    return -1;
                }
            }
        }

        return tokenIdx + expectedWords.size();
    }

    private int matchItem(SrgsItem item, int tokenIdx, List<String> capturedTags, MatchContext ctx, int depth) {
        int minRepeat = item.getMinRepeat();
        int maxRepeat = item.getMaxRepeat();
        SrgsNode child = item.getChild();

        if (child == null) {
            if (item.getTag() != null) capturedTags.add(item.getTag());
            return tokenIdx;
        }

        // Try repeating from maxRepeat down to minRepeat (greedy matching)
        return tryRepeatMatches(child, tokenIdx, 0, minRepeat, maxRepeat, capturedTags, item.getTag(), ctx, depth);
    }

    private int tryRepeatMatches(SrgsNode child, int tokenIdx, int currentCount,
                                 int minRepeat, int maxRepeat,
                                 List<String> capturedTags, String itemTag,
                                 MatchContext ctx, int depth) {
        if (currentCount >= minRepeat) {
            // Can choose to stop repeating here
            if (tokenIdx == ctx.tokens.size()) {
                if (itemTag != null) capturedTags.add(itemTag);
                return tokenIdx;
            }
        }

        if (currentCount < maxRepeat) {
            List<String> localTags = new ArrayList<>();
            int nextIdx = matchNode(child, tokenIdx, localTags, ctx, depth + 1);
            if (nextIdx != -1 && nextIdx > tokenIdx) {
                capturedTags.addAll(localTags);
                int result = tryRepeatMatches(child, nextIdx, currentCount + 1, minRepeat, maxRepeat, capturedTags, itemTag, ctx, depth);
                if (result != -1) {
                    return result;
                }
                // Backtrack tags
                for (int i = 0; i < localTags.size(); i++) {
                    if (!capturedTags.isEmpty()) capturedTags.remove(capturedTags.size() - 1);
                }
            }
        }

        if (currentCount >= minRepeat) {
            if (itemTag != null) capturedTags.add(itemTag);
            return tokenIdx;
        }

        return -1;
    }

    private int matchOneOf(SrgsOneOf oneOf, int tokenIdx, List<String> capturedTags, MatchContext ctx, int depth) {
        for (SrgsNode option : oneOf.getItems()) {
            List<String> optionTags = new ArrayList<>();
            int nextIdx = matchNode(option, tokenIdx, optionTags, ctx, depth + 1);
            if (nextIdx != -1) {
                capturedTags.addAll(optionTags);
                return nextIdx;
            }
        }
        return -1;
    }

    private int matchSequence(SrgsSequence seq, int tokenIdx, List<String> capturedTags, MatchContext ctx, int depth) {
        int curIdx = tokenIdx;
        for (SrgsNode step : seq.getNodes()) {
            int nextIdx = matchNode(step, curIdx, capturedTags, ctx, depth + 1);
            if (nextIdx == -1) {
                return -1;
            }
            curIdx = nextIdx;
        }
        return curIdx;
    }

    private int matchRuleRef(SrgsRuleRef ref, int tokenIdx, List<String> capturedTags, MatchContext ctx, int depth) {
        if (ref.isGarbage()) {
            // GARBAGE can match 0 or more tokens up to next match
            return tokenIdx;
        }
        if (ref.isNull() || ref.isVoid()) {
            return -1;
        }

        SrgsRule targetRule = ctx.grammar.getRule(ref.getRuleId());
        if (targetRule == null || targetRule.getNode() == null) {
            return -1;
        }

        return matchNode(targetRule.getNode(), tokenIdx, capturedTags, ctx, depth + 1);
    }

    // ==========================================
    // Semantic Tag Resolution
    // ==========================================

    private String resolveSemanticValue(List<String> capturedTags, String fallbackText) {
        if (capturedTags.isEmpty()) {
            return fallbackText;
        }

        // Use the last non-blank tag, or combine multiple
        String lastTag = capturedTags.get(capturedTags.size() - 1);
        return SrgsTagEvaluator.evaluate(lastTag, fallbackText);
    }

    // ==========================================
    // Utilities
    // ==========================================

    private static List<String> tokenizeUtterance(String utterance) {
        if (utterance == null) return Collections.emptyList();
        String cleaned = utterance.replaceAll("[^a-zA-Z0-9\\s]", " ").toLowerCase(Locale.ROOT).trim();
        if (cleaned.isEmpty()) return Collections.emptyList();
        return Arrays.asList(cleaned.split("\\s+"));
    }

    private static List<String> splitChars(String s) {
        if (s == null) return Collections.emptyList();
        List<String> list = new ArrayList<>(s.length());
        for (char c : s.toCharArray()) {
            if (!Character.isWhitespace(c)) {
                list.add(String.valueOf(c));
            }
        }
        return list;
    }

    private static class MatchContext {
        final SrgsGrammar grammar;
        final List<String> tokens;
        final boolean isVoiceMode;

        MatchContext(SrgsGrammar grammar, List<String> tokens, boolean isVoiceMode) {
            this.grammar = grammar;
            this.tokens = tokens;
            this.isVoiceMode = isVoiceMode;
        }
    }
}
