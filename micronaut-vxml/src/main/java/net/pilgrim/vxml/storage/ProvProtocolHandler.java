package net.pilgrim.vxml.storage;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/**
 * Protocol handler encapsulating {@code prov://} operations in Object Storage
 * and bounded safe roots.
 */
@Singleton
public class ProvProtocolHandler implements MediaProtocolHandler {

    private final ProvStorageService storageService;

    public ProvProtocolHandler() {
        this(null);
    }

    @Inject
    public ProvProtocolHandler(ProvStorageService storageService) {
        this.storageService = storageService != null ? storageService : ProvStorageRegistry.get();
    }

    @Override
    public String getScheme() {
        return "prov";
    }

    @Override
    public boolean supportsRead(URI uri) {
        return uri != null && "prov".equalsIgnoreCase(uri.getScheme());
    }

    @Override
    public boolean supportsWrite(URI uri) {
        return uri != null && "prov".equalsIgnoreCase(uri.getScheme());
    }

    @Override
    public byte[] read(URI uri) throws IOException {
        Objects.requireNonNull(uri, "uri must not be null");
        return storageService.loadAudio(uri);
    }

    @Override
    public void write(URI uri, byte[] data, String contentType) throws IOException {
        Objects.requireNonNull(uri, "uri must not be null");
        Objects.requireNonNull(data, "data must not be null");
        storageService.storeAudio(uri.toString(), data, contentType);
    }
}
