package net.pilgrim.msml.engine;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.vxml.speech.ToneGenerator;
import net.pilgrim.vxml.speech.TtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Loads audio content for MSML &lt;play&gt; sources (tones, local files, HTTP URLs, and synthetic TTS).
 */
@Singleton
public class MsmlAudioLoader {

    private static final Logger LOG = LoggerFactory.getLogger(MsmlAudioLoader.class);

    private final TtsClient ttsClient;
    private final HttpClient httpClient;

    public MsmlAudioLoader() {
        this(null);
    }

    @Inject
    public MsmlAudioLoader(@Nullable TtsClient ttsClient) {
        this.ttsClient = ttsClient;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public byte[] loadAudio(URI uri) {
        if (uri == null) {
            return new byte[0];
        }
        return loadAudio(uri.toString());
    }

    public byte[] loadAudio(String uriStr) {
        if (uriStr == null || uriStr.isBlank()) {
            return new byte[0];
        }

        String raw = uriStr.trim();

        // 1. Synthetic tone generator
        if (raw.startsWith("tone:") || raw.startsWith("builtin:")) {
            return generateTone(raw);
        }

        // 2. TTS synthesis
        if (raw.startsWith("tts:")) {
            String text = raw.substring(4).trim();
            if (ttsClient != null) {
                try {
                    byte[] audio = ttsClient.synthesize(text, null, null).block(Duration.ofSeconds(5));
                    return audio != null ? audio : new byte[0];
                } catch (Exception e) {
                    LOG.warn("Failed synthesizing TTS prompt: {}", text, e);
                }
            } else {
                LOG.warn("No TtsClient available to synthesize: {}", text);
            }
            return new byte[0];
        }

        // 3. Classpath resource
        if (raw.startsWith("classpath:")) {
            String path = raw.substring("classpath:".length());
            if (path.startsWith("/")) path = path.substring(1);
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = getClass().getClassLoader();
            try (InputStream is = cl.getResourceAsStream(path)) {
                if (is != null) {
                    return is.readAllBytes();
                }
            } catch (Exception e) {
                LOG.warn("Failed reading classpath audio: {}", path, e);
            }
            return new byte[0];
        }

        // 4. HTTP / HTTPS URL
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(raw))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200) {
                    return resp.body();
                } else {
                    LOG.warn("HTTP fetch for audio {} failed with status {}", raw, resp.statusCode());
                }
            } catch (Exception e) {
                LOG.warn("Failed fetching HTTP audio: {}", raw, e);
            }
            return new byte[0];
        }

        // 5. Local file
        try {
            String filePath = raw.startsWith("file://") ? raw.substring(7) : raw;
            Path path = Paths.get(filePath);
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return Files.readAllBytes(path);
            }
        } catch (Exception e) {
            LOG.warn("Failed reading file audio: {}", raw, e);
        }

        return new byte[0];
    }

    private byte[] generateTone(String toneSpec) {
        String spec = toneSpec.substring(toneSpec.lastIndexOf(':') + 1);
        int freq = 1000;
        int durMs = 500;
        if (!spec.isBlank()) {
            String[] parts = spec.split("[,;]");
            try {
                freq = Integer.parseInt(parts[0].trim());
                if (parts.length > 1) {
                    durMs = Integer.parseInt(parts[1].trim());
                }
            } catch (NumberFormatException ignored) {}
        }
        return ToneGenerator.generateTone(freq, durMs);
    }
}
