package net.pilgrim.vxml.storage;

import jakarta.inject.Singleton;
import net.pilgrim.vxml.speech.ToneGenerator;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/**
 * Protocol handler for synthetic tone prompts ({@code tone:<freq>[,<duration_ms>]} and {@code builtin:<spec>}).
 */
@Singleton
public class ToneProtocolHandler implements MediaProtocolHandler {

    @Override
    public String getScheme() {
        return "tone";
    }

    @Override
    public boolean supportsRead(URI uri) {
        if (uri == null) return false;
        String s = uri.getScheme();
        return "tone".equalsIgnoreCase(s) || "builtin".equalsIgnoreCase(s);
    }

    @Override
    public byte[] read(URI uri) throws IOException {
        Objects.requireNonNull(uri, "uri must not be null");
        String uriStr = uri.toString();
        String spec = uriStr.substring(uriStr.lastIndexOf(':') + 1);
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
