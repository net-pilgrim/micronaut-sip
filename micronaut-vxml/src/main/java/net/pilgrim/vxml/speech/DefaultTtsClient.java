package net.pilgrim.vxml.speech;

import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

/**
 * Default Text-to-Speech client implementation.
 * Synthesizes synthetic tones for tone specifications or audible prompt chimes
 * as PCM-16LE 8 kHz mono audio.
 */
@Singleton
public class DefaultTtsClient implements TtsClient {

    @Override
    public Mono<byte[]> synthesize(String text, String voice, String locale) {
        if (text == null || text.isBlank()) {
            return Mono.just(new byte[0]);
        }

        String trimmed = text.trim();
        if (trimmed.startsWith("tone:") || trimmed.startsWith("builtin:")) {
            String spec = trimmed.substring(trimmed.lastIndexOf(':') + 1);
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
            return Mono.just(ToneGenerator.generateTone(freq, durMs));
        }

        // Generate a standard pleasant prompt chime (520 Hz, 400 ms)
        return Mono.just(ToneGenerator.generateTone(520, 400));
    }
}
