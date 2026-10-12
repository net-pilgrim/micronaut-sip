package net.pilgrim.vxml.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Objects;

/**
 * Encapsulating wrapper for media resource URIs (e.g. {@code prov://...}, {@code tone:...}, {@code file://...}).
 * Decouples caller code from protocol-specific details and surfaces clean, unified operations.
 */
public record MediaUri(URI uri) {

    public MediaUri {
        Objects.requireNonNull(uri, "uri must not be null");
    }

    public static MediaUri of(String uriStr) {
        Objects.requireNonNull(uriStr, "uriStr must not be null");
        return new MediaUri(URI.create(uriStr.trim()));
    }

    public static MediaUri of(URI uri) {
        return new MediaUri(uri);
    }

    public static MediaUri prov(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        String clean = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        return new MediaUri(URI.create("prov://" + clean));
    }

    public String getScheme() {
        return uri.getScheme();
    }

    public boolean isProv() {
        return "prov".equalsIgnoreCase(uri.getScheme());
    }

    public boolean isTone() {
        return "tone".equalsIgnoreCase(uri.getScheme()) || "builtin".equalsIgnoreCase(uri.getScheme());
    }

    public boolean isFile() {
        return uri.getScheme() == null || "file".equalsIgnoreCase(uri.getScheme());
    }

    public boolean isHttp() {
        return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
    }

    public InputStream openStream() throws IOException {
        return uri.toURL().openStream();
    }

    public URL toURL() throws IOException {
        return uri.toURL();
    }

    @Override
    public String toString() {
        return uri.toString();
    }
}
