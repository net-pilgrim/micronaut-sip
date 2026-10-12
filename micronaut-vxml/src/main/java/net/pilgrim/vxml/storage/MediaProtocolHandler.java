package net.pilgrim.vxml.storage;

import java.io.IOException;
import java.net.URI;

/**
 * Pluggable handler interface for audio protocol resolution and persistence.
 * Implementations encapsulate specific URI schemes (such as {@code prov://}, {@code tone:}, {@code file://}).
 */
public interface MediaProtocolHandler {

    /**
     * Primary URI scheme handled (e.g. "prov", "file", "tone", "classpath", "http").
     */
    String getScheme();

    /**
     * Checks if this handler supports reading audio from the given URI.
     */
    default boolean supportsRead(URI uri) {
        return uri != null && getScheme().equalsIgnoreCase(uri.getScheme());
    }

    /**
     * Checks if this handler supports persisting audio to the given URI.
     */
    default boolean supportsWrite(URI uri) {
        return false;
    }

    /**
     * Reads audio bytes from the given URI.
     */
    byte[] read(URI uri) throws IOException;

    /**
     * Persists audio bytes to the given URI.
     */
    default void write(URI uri, byte[] data, String contentType) throws IOException {
        throw new UnsupportedOperationException("Write not supported for scheme: " + getScheme());
    }
}
