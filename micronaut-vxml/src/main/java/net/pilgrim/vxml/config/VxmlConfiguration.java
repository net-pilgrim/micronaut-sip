package net.pilgrim.vxml.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for VoiceXML 2.1 interpreter and document loading.
 */
@ConfigurationProperties("vxml")
public class VxmlConfiguration {

    private boolean enabled = true;
    private boolean httpEnabled = false;
    private int httpConnectTimeoutMs = 3000;
    private int httpReadTimeoutMs = 5000;
    private int maxDocumentSizeBytes = 5 * 1024 * 1024; // 5 MB
    private List<String> allowedHosts = new ArrayList<>();
    private boolean ssrfProtectionEnabled = true;
    private String documentDirectory = "/var/vxml";
    private long defaultTimeoutMs = 5000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isHttpEnabled() {
        return httpEnabled;
    }

    public void setHttpEnabled(boolean httpEnabled) {
        this.httpEnabled = httpEnabled;
    }

    public int getHttpConnectTimeoutMs() {
        return httpConnectTimeoutMs;
    }

    public void setHttpConnectTimeoutMs(int httpConnectTimeoutMs) {
        this.httpConnectTimeoutMs = httpConnectTimeoutMs;
    }

    public int getHttpReadTimeoutMs() {
        return httpReadTimeoutMs;
    }

    public void setHttpReadTimeoutMs(int httpReadTimeoutMs) {
        this.httpReadTimeoutMs = httpReadTimeoutMs;
    }

    public int getMaxDocumentSizeBytes() {
        return maxDocumentSizeBytes;
    }

    public void setMaxDocumentSizeBytes(int maxDocumentSizeBytes) {
        this.maxDocumentSizeBytes = maxDocumentSizeBytes;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts != null ? allowedHosts : new ArrayList<>();
    }

    public boolean isSsrfProtectionEnabled() {
        return ssrfProtectionEnabled;
    }

    public void setSsrfProtectionEnabled(boolean ssrfProtectionEnabled) {
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
    }

    public String getDocumentDirectory() {
        return documentDirectory;
    }

    public void setDocumentDirectory(String documentDirectory) {
        this.documentDirectory = documentDirectory;
    }

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }
}
