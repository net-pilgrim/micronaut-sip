package net.pilgrim.vxml.loader;

import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;
import net.pilgrim.vxml.speech.ToneGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Default implementation of {@link VxmlAudioLoader} capable of loading tones,
 * classpath audio resources, and local audio files.
 */
@Singleton
@Secondary
public class DefaultVxmlAudioLoader implements VxmlAudioLoader {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultVxmlAudioLoader.class);

    @Override
    public byte[] loadAudio(String uri) {
        if (uri == null || uri.isBlank()) {
            return new byte[0];
        }

        String rawUri = uri.trim();

        // 1. Tones and builtins: tone:freq[,durMs] or builtin:beep
        if (rawUri.startsWith("tone:") || rawUri.startsWith("builtin:")) {
            String spec = rawUri.substring(rawUri.lastIndexOf(':') + 1);
            int freq = 440;
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

        // 2. Classpath resources: classpath:...
        if (rawUri.startsWith("classpath:")) {
            String resourcePath = rawUri.substring("classpath:".length());
            if (resourcePath.startsWith("/")) {
                resourcePath = resourcePath.substring(1);
            }
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = getClass().getClassLoader();
            }
            try (InputStream is = cl.getResourceAsStream(resourcePath)) {
                if (is != null) {
                    return is.readAllBytes();
                }
            } catch (Exception e) {
                LOG.warn("Failed to load classpath audio: {}", resourcePath, e);
            }
            return new byte[0];
        }

        // 3. Local filesystem files
        try {
            String filePath = rawUri.startsWith("file://") ? rawUri.substring("file://".length()) : rawUri;
            Path path = Paths.get(filePath);
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return Files.readAllBytes(path);
            }
        } catch (Exception e) {
            LOG.warn("Failed to load file audio: {}", rawUri, e);
        }

        return new byte[0];
    }
}
