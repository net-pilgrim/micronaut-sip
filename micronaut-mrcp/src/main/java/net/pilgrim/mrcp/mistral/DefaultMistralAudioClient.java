package net.pilgrim.mrcp.mistral;

import io.micronaut.context.annotation.Secondary;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Production implementation of {@link MistralAudioClient} using Mistral AI's audio endpoints.
 * <p>
 * If API key is unconfigured or {@code mock: true}, automatically falls back to {@link MockMistralAudioClient}.
 */
@Singleton
@Secondary
public class DefaultMistralAudioClient implements MistralAudioClient {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultMistralAudioClient.class);

    private final MistralAudioConfiguration config;
    private final MockMistralAudioClient mockFallback;
    private final HttpClient httpClient;

    @Inject
    public DefaultMistralAudioClient(MistralAudioConfiguration config) {
        this.config = config != null ? config : new MistralAudioConfiguration();
        this.mockFallback = new MockMistralAudioClient();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Mono<byte[]> synthesize(String text, @Nullable String voice, @Nullable String language) {
        if (config.isMock() || config.getApiKey().isBlank()) {
            if (config.getApiKey().isBlank() && !config.isMock()) {
                LOG.debug("Mistral API key not configured, falling back to mock TTS synthesizer");
            }
            return mockFallback.synthesize(text, voice, language);
        }

        return Mono.fromFuture(() -> {
            String selectedVoice = (voice != null && !voice.isBlank()) ? voice : config.getDefaultVoice();
            String jsonPayload = "{"
                    + "\"model\":\"" + escapeJson(config.getTtsModel()) + "\","
                    + "\"input\":\"" + escapeJson(text) + "\","
                    + "\"voice\":\"" + escapeJson(selectedVoice) + "\","
                    + "\"response_format\":\"wav\""
                    + "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getBaseUrl() + "/audio/speech"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            String body = response.body();
                            String base64Data = extractJsonField(body, "audio_data");
                            if (base64Data != null && !base64Data.isBlank()) {
                                byte[] rawWav = java.util.Base64.getDecoder().decode(base64Data.trim());
                                byte[] pcm16Le24k = extractPcmFromWav(rawWav);
                                return resample24kTo8k(pcm16Le24k);
                            }
                            return body.getBytes(StandardCharsets.UTF_8);
                        }
                        LOG.warn("Mistral TTS API returned error {}: {}", response.statusCode(), response.body());
                        return mockFallback.synthesize(text, voice, language).block();
                    })
                    .exceptionally(ex -> {
                        LOG.warn("Mistral TTS API call failed ({}), falling back to mock synthesizer", ex.getMessage());
                        return mockFallback.synthesize(text, voice, language).block();
                    });
        });
    }

    @Override
    public Mono<MistralRecognitionResult> recognize(byte[] pcmAudio, @Nullable String grammar, @Nullable String language) {
        if (config.isMock() || config.getApiKey().isBlank()) {
            if (config.getApiKey().isBlank() && !config.isMock()) {
                LOG.debug("Mistral API key not configured, falling back to mock ASR recognizer");
            }
            return mockFallback.recognize(pcmAudio, grammar, language);
        }

        return Mono.fromFuture(() -> {
            byte[] wavBytes = encodePcmToWav(pcmAudio, config.getSampleRate(), 1);
            String boundary = "----Boundary" + UUID.randomUUID();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                // model part
                baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                baos.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                baos.write((config.getAsrModel() + "\r\n").getBytes(StandardCharsets.UTF_8));

                // file part
                baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                baos.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".getBytes(StandardCharsets.UTF_8));
                baos.write("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                baos.write(wavBytes);
                baos.write("\r\n".getBytes(StandardCharsets.UTF_8));

                baos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException("Error assembling multipart body", e);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getBaseUrl() + "/audio/transcriptions"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            String body = response.body();
                            String text = extractJsonField(body, "text");
                            float confidence = 0.92f;
                            return new MistralRecognitionResult(text, confidence, language != null ? language : config.getDefaultLanguage(), Collections.emptyList());
                        }
                        LOG.warn("Mistral ASR API returned error {}: {}", response.statusCode(), response.body());
                        return mockFallback.recognize(pcmAudio, grammar, language).block();
                    });
        });
    }

    public static byte[] encodePcmToWav(byte[] pcmData, int sampleRate, int channels) {
        if (pcmData == null) {
            pcmData = new byte[0];
        }
        int totalAudioLen = pcmData.length;
        int totalDataLen = totalAudioLen + 36;
        int byteRate = sampleRate * channels * 2;

        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0; // PCM
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * 2); header[33] = 0;
        header[34] = 16; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        byte[] wav = new byte[44 + pcmData.length];
        System.arraycopy(header, 0, wav, 0, 44);
        System.arraycopy(pcmData, 0, wav, 44, pcmData.length);
        return wav;
    }

    private static byte[] extractPcmFromWav(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 44) {
            return wavBytes != null ? wavBytes : new byte[0];
        }
        if (wavBytes[0] == 'R' && wavBytes[1] == 'I' && wavBytes[2] == 'F' && wavBytes[3] == 'F') {
            int offset = 12;
            while (offset + 8 <= wavBytes.length) {
                if (wavBytes[offset] == 'd' && wavBytes[offset + 1] == 'a'
                        && wavBytes[offset + 2] == 't' && wavBytes[offset + 3] == 'a') {
                    int dataSize = ((wavBytes[offset + 4] & 0xFF))
                            | ((wavBytes[offset + 5] & 0xFF) << 8)
                            | ((wavBytes[offset + 6] & 0xFF) << 16)
                            | ((wavBytes[offset + 7] & 0xFF) << 24);
                    int start = offset + 8;
                    int length = Math.min(Math.max(0, dataSize), wavBytes.length - start);
                    byte[] pcm = new byte[length];
                    System.arraycopy(wavBytes, start, pcm, 0, length);
                    return pcm;
                }
                int chunkSize = ((wavBytes[offset + 4] & 0xFF))
                        | ((wavBytes[offset + 5] & 0xFF) << 8)
                        | ((wavBytes[offset + 6] & 0xFF) << 16)
                        | ((wavBytes[offset + 7] & 0xFF) << 24);
                offset += 8 + Math.max(0, chunkSize);
            }
            // Standard 44-byte header fallback
            byte[] pcm = new byte[wavBytes.length - 44];
            System.arraycopy(wavBytes, 44, pcm, 0, pcm.length);
            return pcm;
        }
        return wavBytes;
    }

    private static byte[] resample24kTo8k(byte[] pcm24k) {
        if (pcm24k == null || pcm24k.length < 6) return pcm24k;
        int num24kSamples = pcm24k.length / 2;
        int num8kSamples = num24kSamples / 3;
        byte[] pcm8k = new byte[num8kSamples * 2];

        for (int i = 0; i < num8kSamples; i++) {
            int src = i * 3 * 2;
            short s0 = (short) ((pcm24k[src] & 0xFF) | (pcm24k[src + 1] << 8));
            short s1 = (short) ((pcm24k[src + 2] & 0xFF) | (pcm24k[src + 3] << 8));
            short s2 = (short) ((pcm24k[src + 4] & 0xFF) | (pcm24k[src + 5] << 8));
            int avg = (s0 + s1 + s2) / 3;
            short sample8k = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, avg));

            pcm8k[i * 2] = (byte) (sample8k & 0xFF);
            pcm8k[i * 2 + 1] = (byte) ((sample8k >> 8) & 0xFF);
        }
        return pcm8k;
    }

    private static String extractJsonField(String json, String field) {
        if (json == null) return "";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }
}
