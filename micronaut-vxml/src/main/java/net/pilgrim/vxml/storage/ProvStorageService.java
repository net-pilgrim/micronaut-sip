package net.pilgrim.vxml.storage;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.objectstorage.ObjectStorageEntry;
import io.micronaut.objectstorage.ObjectStorageOperations;
import io.micronaut.objectstorage.request.UploadRequest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;

/**
 * Secure media storage and retrieval service implementing the {@code prov://} protocol.
 * <p>
 * Mitigates data exfiltration vulnerabilities by restricting media access strictly
 * to relative keys within a safe Object Storage root or bounded directory.
 */
@Singleton
public class ProvStorageService {

    private static final Logger LOG = LoggerFactory.getLogger(ProvStorageService.class);
    public static final String SCHEME = "prov";
    public static final String PROTOCOL_PREFIX = "prov://";

    private final ObjectStorageOperations<?, ?, ?> objectStorage;
    private final ProvStorageConfiguration config;

    public ProvStorageService() {
        this(null, null);
    }

    @Inject
    public ProvStorageService(@Nullable ObjectStorageOperations<?, ?, ?> objectStorage,
                              @Nullable ProvStorageConfiguration config) {
        this.objectStorage = objectStorage;
        this.config = config != null ? config : new ProvStorageConfiguration();
        ProvStorageRegistry.set(this);
    }

    public ProvStorageConfiguration getConfiguration() {
        return config;
    }

    public ObjectStorageOperations<?, ?, ?> getObjectStorage() {
        return objectStorage;
    }

    /**
     * Checks if the given URI string targets the provisioned storage protocol ({@code prov://}).
     */
    public boolean isProvUri(String uriStr) {
        if (uriStr == null || uriStr.isBlank()) {
            return false;
        }
        String s = uriStr.trim().toLowerCase();
        return s.startsWith("prov://") || s.startsWith("prov:/") || s.startsWith("prov:");
    }

    /**
     * Checks if the given URI targets the {@code prov} scheme.
     */
    public boolean isProvUri(URI uri) {
        return uri != null && uri.getScheme() != null && SCHEME.equalsIgnoreCase(uri.getScheme());
    }

    /**
     * Extracts and validates the relative storage path from a {@code prov://} URI.
     * Prevents directory traversal attacks ({@code ..}, backslashes, percent-encoding, null bytes).
     *
     * @param uriStr URI string (e.g. {@code prov://prompts/welcome.wav})
     * @return clean, normalized relative key (e.g. {@code prompts/welcome.wav})
     * @throws SecurityException if path traversal is detected
     */
    public String extractRelativePath(String uriStr) {
        Objects.requireNonNull(uriStr, "uriStr must not be null");
        String trimmed = uriStr.trim();

        if (trimmed.contains("\0")) {
            throw new SecurityException("Null byte detected in URI: " + uriStr);
        }

        // Strip prov:// or prov: prefix
        String rawPath;
        if (trimmed.regionMatches(true, 0, PROTOCOL_PREFIX, 0, PROTOCOL_PREFIX.length())) {
            rawPath = trimmed.substring(PROTOCOL_PREFIX.length());
        } else if (trimmed.regionMatches(true, 0, "prov:/", 0, 6)) {
            rawPath = trimmed.substring(6);
        } else if (trimmed.regionMatches(true, 0, "prov:", 0, 5)) {
            rawPath = trimmed.substring(5);
        } else {
            rawPath = trimmed;
        }

        // URL decode to catch %2e%2e, %2f, etc.
        String decoded;
        try {
            decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            decoded = rawPath;
        }

        // Normalize slashes
        decoded = decoded.replace('\\', '/');

        // Strip leading slashes to ensure it's relative
        while (decoded.startsWith("/")) {
            decoded = decoded.substring(1);
        }

        if (decoded.isBlank()) {
            throw new IllegalArgumentException("prov:// URI specifies an empty relative path: " + uriStr);
        }

        // Verify segments for path traversal
        String[] segments = decoded.split("/");
        for (String segment : segments) {
            String s = segment.trim();
            if ("..".equals(s) || ".".equals(s)) {
                LOG.error("Path traversal attempt blocked in prov:// URI: {}", uriStr);
                throw new SecurityException("Path traversal attempt in prov:// URI: " + uriStr);
            }
        }

        return decoded;
    }

    /**
     * Loads audio bytes for a {@code prov://} asset from Object Storage or safe root location.
     */
    public byte[] loadAudio(String uriStr) {
        if (!isProvUri(uriStr)) {
            return new byte[0];
        }

        String relativePath = extractRelativePath(uriStr);
        String key = resolveKey(relativePath);

        // 1. Retrieve from Object Storage if available
        if (objectStorage != null) {
            try {
                Optional<? extends ObjectStorageEntry<?>> entry = objectStorage.retrieve(key);
                if (entry.isPresent()) {
                    try (InputStream is = entry.get().getInputStream()) {
                        byte[] bytes = is.readAllBytes();
                        LOG.debug("Loaded prov:// asset from object storage: key={}, {} bytes", key, bytes.length);
                        return bytes;
                    }
                }
            } catch (Exception e) {
                LOG.warn("Error retrieving prov:// asset from object storage (key: {}): {}", key, e.getMessage());
            }
        }

        // 2. Safe root filesystem fallback
        try {
            Path safeRoot = config.getSafeRootDirectory();
            Path target = safeRoot.resolve(relativePath).normalize();

            // Strict boundary enforcement
            if (!target.startsWith(safeRoot)) {
                LOG.error("Resolved path {} escapes safe root directory {}", target, safeRoot);
                throw new SecurityException("Resolved path escapes safe root location: " + target);
            }

            if (Files.exists(target) && Files.isRegularFile(target)) {
                byte[] bytes = Files.readAllBytes(target);
                LOG.debug("Loaded prov:// asset from safe root directory: path={}, {} bytes", target, bytes.length);
                return bytes;
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("Failed reading prov:// asset from safe root: {}", relativePath, e);
        }

        LOG.warn("prov:// asset not found in storage: {}", relativePath);
        return new byte[0];
    }

    public byte[] loadAudio(URI uri) {
        if (uri == null) return new byte[0];
        return loadAudio(uri.toString());
    }

    /**
     * Stores recorded audio bytes into Object Storage and safe root location.
     *
     * @param uriStr destination prov:// URI (e.g. {@code prov://recordings/msg-1.wav})
     * @param audioBytes WAV or PCM audio bytes
     * @param contentType MIME type (default {@code "audio/wav"})
     * @return storage key under which the asset was persisted
     */
    public String storeAudio(String uriStr, byte[] audioBytes, String contentType) {
        Objects.requireNonNull(audioBytes, "audioBytes must not be null");
        String relativePath = extractRelativePath(uriStr);
        String key = resolveKey(relativePath);
        String mime = (contentType != null && !contentType.isBlank()) ? contentType : "audio/wav";

        // 1. Upload to Object Storage if available
        if (objectStorage != null) {
            try {
                UploadRequest uploadRequest = UploadRequest.fromBytes(audioBytes, key, mime);
                objectStorage.upload(uploadRequest);
                LOG.info("Uploaded prov:// recording to object storage with key: {} ({} bytes)", key, audioBytes.length);
            } catch (Exception e) {
                LOG.error("Failed uploading prov:// recording to object storage with key: {}", key, e);
                throw new RuntimeException("Object storage upload failed for key: " + key, e);
            }
        }

        // 2. Persist to safe root directory
        try {
            Path safeRoot = config.getSafeRootDirectory();
            Path target = safeRoot.resolve(relativePath).normalize();

            if (!target.startsWith(safeRoot)) {
                LOG.error("Resolved path {} escapes safe root directory {}", target, safeRoot);
                throw new SecurityException("Resolved path escapes safe root location: " + target);
            }

            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, audioBytes);
            LOG.info("Persisted prov:// recording to safe root location: {}", target);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("Failed writing prov:// recording to safe root: {}", relativePath, e);
        }

        return key;
    }

    public String storeAudio(String uriStr, byte[] audioBytes) {
        return storeAudio(uriStr, audioBytes, "audio/wav");
    }

    /**
     * Validates whether access to a given {@code file://} URI is permissible.
     * Rejects access when {@code prov.storage.allow-file-protocol} is false,
     * or when the requested file escapes the safe root directory.
     *
     * @param fileUriStr file URI or path
     * @return true if permitted, false if blocked
     */
    public boolean isFileAllowed(String fileUriStr) {
        if (fileUriStr == null || fileUriStr.isBlank()) {
            return false;
        }

        if (!config.isAllowFileProtocol()) {
            LOG.warn("Access to file:// URI blocked to prevent data exfiltration: '{}'. Use prov:// instead.", fileUriStr);
            return false;
        }

        try {
            String pathStr = fileUriStr.startsWith("file://") ? fileUriStr.substring(7) : fileUriStr;
            Path resolved = Paths.get(pathStr).toAbsolutePath().normalize();
            Path safeRoot = config.getSafeRootDirectory();

            if (!resolved.startsWith(safeRoot)) {
                LOG.error("Path traversal blocked: file:// path '{}' escapes safe root directory '{}'", fileUriStr, safeRoot);
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.warn("Invalid file:// URI format: {}", fileUriStr);
            return false;
        }
    }

    private String resolveKey(String relativePath) {
        String prefix = config.getSafeRootPrefix();
        if (prefix == null || prefix.isBlank()) {
            return relativePath;
        }
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix + relativePath;
    }
}
