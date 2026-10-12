package net.pilgrim.vxml.storage;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration for the secure {@code prov://} storage protocol and safe root boundary enforcement.
 */
@ConfigurationProperties("prov.storage")
public class ProvStorageConfiguration {

    private String safeRootPrefix = "";
    private String safeRootPath = "build/storage/media";
    private boolean allowFileProtocol = false;

    public String getSafeRootPrefix() {
        return safeRootPrefix;
    }

    public void setSafeRootPrefix(String safeRootPrefix) {
        this.safeRootPrefix = safeRootPrefix != null ? safeRootPrefix : "";
    }

    public String getSafeRootPath() {
        return safeRootPath;
    }

    public void setSafeRootPath(String safeRootPath) {
        this.safeRootPath = safeRootPath != null ? safeRootPath : "build/storage/media";
    }

    public Path getSafeRootDirectory() {
        return Paths.get(safeRootPath).toAbsolutePath().normalize();
    }

    /**
     * When false (default), access to arbitrary {@code file://} URIs is strictly disallowed
     * to prevent host filesystem data exfiltration. All media should use {@code prov://}.
     */
    public boolean isAllowFileProtocol() {
        return allowFileProtocol;
    }

    public void setAllowFileProtocol(boolean allowFileProtocol) {
        this.allowFileProtocol = allowFileProtocol;
    }
}
