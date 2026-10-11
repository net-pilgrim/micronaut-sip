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

    private final ConcurrentMap<String, String> grammarKeywordMap = new ConcurrentHashMap<>();
    private volatile String defaultRecognizedText = "hello mistral";
    private volatile float defaultConfidence = 0.95f;

    public MockMistralAudioClient() {
        grammarKeywordMap.put("yes", "yes");
        grammarKeywordMap.put("no", "no");
        grammarKeywordMap.put("help", "help");
        grammarKeywordMap.put("sales", "sales");
        grammarKeywordMap.put("support", "support");
        grammarKeywordMap.put("operator", "operator");
    }

    public void setDefaultRecognizedText(String text, float confidence) {
        this.defaultRecognizedText = text != null ? text : "";
        this.defaultConfidence = confidence;
    }

    public void mapGrammarKeyword(String keyword, String recognizedText) {
        if (keyword != null && recognizedText != null) {
            grammarKeywordMap.put(keyword.toLowerCase(java.util.Locale.ROOT), recognizedText);
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
                for (var entry : grammarKeywordMap.entrySet()) {
                    if (gLower.contains(entry.getKey())) {
                        List<String> nBest = new ArrayList<>();
                        nBest.add(entry.getValue());
                        return new MistralRecognitionResult(entry.getValue(), 0.98f, language != null ? language : "en-US", nBest);
                    }
                }
            }

            List<String> nBest = Collections.singletonList(defaultRecognizedText);
            return new MistralRecognitionResult(defaultRecognizedText, defaultConfidence, language != null ? language : "en-US", nBest);
        });
    }
}
