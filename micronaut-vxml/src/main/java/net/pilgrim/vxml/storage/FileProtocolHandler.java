package net.pilgrim.vxml.storage;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Protocol handler for {@code file://} resources, enforcing boundary checks
 * to prevent host filesystem data exfiltration.
 */
@Singleton
public class FileProtocolHandler implements MediaProtocolHandler {

    private static final Logger LOG = LoggerFactory.getLogger(FileProtocolHandler.class);

    private final ProvStorageService storageService;

    public FileProtocolHandler() {
        this(null);
    }

    @Inject
    public FileProtocolHandler(ProvStorageService storageService) {
        this.storageService = storageService != null ? storageService : ProvStorageRegistry.get();
    }

    @Override
    public String getScheme() {
        return "file";
    }

    @Override
    public boolean supportsRead(URI uri) {
        return uri != null && (uri.getScheme() == null || "file".equalsIgnoreCase(uri.getScheme()));
    }

    @Override
    public boolean supportsWrite(URI uri) {
        return uri != null && (uri.getScheme() == null || "file".equalsIgnoreCase(uri.getScheme()));
    }

    @Override
    public byte[] read(URI uri) throws IOException {
        Objects.requireNonNull(uri, "uri must not be null");
        if (!storageService.isFileAllowed(uri.toString())) {
            LOG.warn("Rejected file read for '{}' to prevent data exfiltration. Use prov:// instead.", uri);
            return new byte[0];
        }

        try {
            String pathStr = uri.getPath() != null ? uri.getPath() : uri.getSchemeSpecificPart();
            Path path = Paths.get(pathStr);
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return Files.readAllBytes(path);
            }
        } catch (Exception e) {
            LOG.warn("Failed reading file: {}", uri, e);
        }
        return new byte[0];
    }

    @Override
    public void write(URI uri, byte[] data, String contentType) throws IOException {
        Objects.requireNonNull(uri, "uri must not be null");
        Objects.requireNonNull(data, "data must not be null");
        if (!storageService.isFileAllowed(uri.toString())) {
            LOG.warn("Rejected file write to '{}' to prevent unauthorized host filesystem write. Use prov:// instead.", uri);
            throw new SecurityException("Writing to file:// rejected to prevent arbitrary filesystem write: " + uri);
        }

        String pathStr = uri.getPath() != null ? uri.getPath() : uri.getSchemeSpecificPart();
        Path path = Paths.get(pathStr);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, data);
    }
}
