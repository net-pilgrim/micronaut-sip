package net.pilgrim.vxml.storage;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry coordinating pluggable {@link MediaProtocolHandler}s.
 * Provides unified, protocol-agnostic read and write operations across all supported URI schemes.
 */
@Singleton
public class MediaProtocolRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(MediaProtocolRegistry.class);

    private final List<MediaProtocolHandler> handlers = new CopyOnWriteArrayList<>();

    public MediaProtocolRegistry() {
        this(null);
    }

    @Inject
    public MediaProtocolRegistry(@Nullable List<MediaProtocolHandler> injectedHandlers) {
        if (injectedHandlers != null && !injectedHandlers.isEmpty()) {
            handlers.addAll(injectedHandlers);
        } else {
            // Default built-in handlers
            ProvStorageService provStorage = ProvStorageRegistry.get();
            handlers.add(new ProvProtocolHandler(provStorage));
            handlers.add(new FileProtocolHandler(provStorage));
            handlers.add(new ToneProtocolHandler());
            handlers.add(new ClasspathProtocolHandler());
            handlers.add(new HttpProtocolHandler());
            handlers.add(new TtsProtocolHandler(null));
        }
    }

    public void register(MediaProtocolHandler handler) {
        if (handler != null) {
            // Insert at beginning so custom handlers take precedence
            handlers.add(0, handler);
        }
    }

    public Optional<MediaProtocolHandler> findReadHandler(URI uri) {
        if (uri == null) return Optional.empty();
        return handlers.stream()
                .filter(h -> h.supportsRead(uri))
                .findFirst();
    }

    public Optional<MediaProtocolHandler> findWriteHandler(URI uri) {
        if (uri == null) return Optional.empty();
        return handlers.stream()
                .filter(h -> h.supportsWrite(uri))
                .findFirst();
    }

    public byte[] read(URI uri) {
        if (uri == null) return new byte[0];
        Optional<MediaProtocolHandler> handler = findReadHandler(uri);
        if (handler.isPresent()) {
            try {
                return handler.get().read(uri);
            } catch (IOException e) {
                LOG.warn("Failed reading media from {}: {}", uri, e.getMessage());
                return new byte[0];
            }
        }
        LOG.warn("No MediaProtocolHandler found for URI scheme: {}", uri);
        return new byte[0];
    }

    public byte[] read(String uriStr) {
        if (uriStr == null || uriStr.isBlank()) return new byte[0];
        try {
            return read(URI.create(uriStr.trim()));
        } catch (Exception e) {
            LOG.warn("Invalid URI for media read: {}", uriStr);
            return new byte[0];
        }
    }

    public void write(URI uri, byte[] data, String contentType) throws IOException {
        if (uri == null || data == null) return;
        Optional<MediaProtocolHandler> handler = findWriteHandler(uri);
        if (handler.isPresent()) {
            handler.get().write(uri, data, contentType);
        } else {
            throw new UnsupportedOperationException("No write handler available for URI scheme: " + uri);
        }
    }

    public void write(String uriStr, byte[] data, String contentType) throws IOException {
        if (uriStr == null || uriStr.isBlank()) return;
        write(URI.create(uriStr.trim()), data, contentType);
    }
}
