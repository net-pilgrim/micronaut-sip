package net.pilgrim.mrcp.mistral;

import io.micronaut.core.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory deterministic mock implementation of {@link MistralAudioClient}
 * suitable for unit tests, offline development, and continuous integration.
 */
public class MockMistralAudioClient implements MistralAudioClient {

    private static final Logger LOG = LoggerFactory.getLogger(MockMistralAudioClient.class);
    private static final int SAMPLE_RATE = 8000;

    private final ConcurrentMap<String, String> defaultKeywordMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> customKeywordMap = new ConcurrentHashMap<>();
    private volatile String defaultRecognizedText = "hello mistral";
    private volatile float defaultConfidence = 0.95f;

    public MockMistralAudioClient() {
        defaultKeywordMap.put("yes", "yes");
        defaultKeywordMap.put("no", "no");
        defaultKeywordMap.put("help", "help");
        defaultKeywordMap.put("sales", "sales");
        defaultKeywordMap.put("support", "support");
        defaultKeywordMap.put("operator", "operator");
    }

    public void setDefaultRecognizedText(String text, float confidence) {
        this.defaultRecognizedText = text != null ? text : "";
        this.defaultConfidence = confidence;
    }

    public void mapGrammarKeyword(String keyword, String recognizedText) {
        if (keyword != null && recognizedText != null) {
            customKeywordMap.put(keyword.toLowerCase(java.util.Locale.ROOT), recognizedText);
        }
    }

    @Override
    public Mono<byte[]> synthesize(String text, @Nullable String voice, @Nullable String language) {
        return Mono.fromSupplier(() -> {
            LOG.debug("MockMistralAudioClient: synthesizing prompt '{}' (voice={}, lang={})", text, voice, language);
            if (text == null || text.isBlank()) {
                return new byte[0];
            }

            // Estimate duration based on word count: ~150 ms per word, min 400 ms, max 5000 ms
            int wordCount = Math.max(1, text.trim().split("\\s+").length);
            int durationMs = Math.min(5000, Math.max(400, wordCount * 150));
            int numSamples = (SAMPLE_RATE * durationMs) / 1000;

            byte[] pcm = new byte[numSamples * 2];
            double freq = 440.0; // standard concert A tone
            for (int i = 0; i < numSamples; i++) {
                double angle = 2.0 * Math.PI * freq * i / SAMPLE_RATE;
                short sample = (short) (Math.sin(angle) * 4000); // comfortable amplitude
                pcm[i * 2] = (byte) (sample & 0xFF);
                pcm[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
            }

            return pcm;
        });
    }

    @Override
    public Mono<MistralRecognitionResult> recognize(byte[] pcmAudio, @Nullable String grammar, @Nullable String language) {
        return Mono.fromSupplier(() -> {
            int audioLen = (pcmAudio != null) ? pcmAudio.length : 0;
            LOG.debug("MockMistralAudioClient: recognizing {} bytes of PCM audio (grammar={}, lang={})",
                    audioLen, grammar, language);

            if (grammar != null && !grammar.isBlank()) {
                String gLower = grammar.toLowerCase(java.util.Locale.ROOT);

                // 1. Check custom keywords first
                String bestCustomMatch = null;
                int bestCustomPos = Integer.MAX_VALUE;
                for (var entry : customKeywordMap.entrySet()) {
                    int pos = gLower.indexOf(entry.getKey());
                    if (pos != -1 && pos < bestCustomPos) {
                        bestCustomPos = pos;
                        bestCustomMatch = entry.getValue();
                    }
                }
                if (bestCustomMatch != null) {
                    List<String> nBest = new ArrayList<>();
                    nBest.add(bestCustomMatch);
                    return new MistralRecognitionResult(bestCustomMatch, 0.98f, language != null ? language : "en-US", nBest);
                }

                // 2. Check default keywords in order of appearance in grammar
                String bestDefaultMatch = null;
                int bestDefaultPos = Integer.MAX_VALUE;
                for (var entry : defaultKeywordMap.entrySet()) {
                    int pos = gLower.indexOf(entry.getKey());
                    if (pos != -1 && pos < bestDefaultPos) {
                        bestDefaultPos = pos;
                        bestDefaultMatch = entry.getValue();
                    }
                }
                if (bestDefaultMatch != null) {
                    List<String> nBest = new ArrayList<>();
                    nBest.add(bestDefaultMatch);
                    return new MistralRecognitionResult(bestDefaultMatch, 0.98f, language != null ? language : "en-US", nBest);
                }

                // Check for first <item> or token in SRGS grammar
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("<item[^>]*>([^<]+?)(?:<tag>[^<]*</tag>)?</item>").matcher(grammar);
                if (m.find()) {
                    String matchedWord = m.group(1).trim();
                    List<String> nBest = new ArrayList<>();
                    nBest.add(matchedWord);
                    return new MistralRecognitionResult(matchedWord, 0.95f, language != null ? language : "en-US", nBest);
                }
            }

            List<String> nBest = Collections.singletonList(defaultRecognizedText);
            return new MistralRecognitionResult(defaultRecognizedText, defaultConfidence, language != null ? language : "en-US", nBest);
        });
    }
}
