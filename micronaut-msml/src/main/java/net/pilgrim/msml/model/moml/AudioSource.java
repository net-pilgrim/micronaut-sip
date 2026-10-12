package net.pilgrim.msml.model.moml;

import java.net.URI;
import java.util.Objects;

/**
 * Audio source reference inside MOML &lt;play&gt; (RFC 5707 Section 4.2).
 * Supports HTTP/HTTPS URLs, file URLs, and synthetic tone specifications (e.g. {@code tone:1000,500}).
 */
public record AudioSource(URI uri, String format) {

    public AudioSource {
        Objects.requireNonNull(uri, "uri must not be null");
    }

    public static AudioSource of(URI uri) {
        return new AudioSource(uri, "audio/wav");
    }

    public static AudioSource of(String uriStr) {
        return new AudioSource(URI.create(uriStr), "audio/wav");
    }

    public static AudioSource of(String uriStr, String format) {
        return new AudioSource(URI.create(uriStr), format != null ? format : "audio/wav");
    }

    public boolean isTone() {
        return uri.getScheme() != null && "tone".equalsIgnoreCase(uri.getScheme());
    }

    public boolean isTts() {
        return uri.getScheme() != null && "tts".equalsIgnoreCase(uri.getScheme());
    }
}
