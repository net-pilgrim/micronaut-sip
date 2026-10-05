package net.pilgrim.netann.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for RFC 4240 NetAnn Announcement Media Server.
 */
@ConfigurationProperties("netann")
public class NetannConfiguration {

    // Audio Loading & Security Controls
    private boolean httpEnabled = false;
    private int httpConnectTimeoutMs = 3000;
    private int httpReadTimeoutMs = 5000;
    private int maxAudioSizeBytes = 10 * 1024 * 1024; // 10 MB
    private List<String> allowedHosts = new ArrayList<>();
    private boolean ssrfProtectionEnabled = true;
    private String promptDirectory = "/var/netann/prompts";

    // Call Duration & Resource Caps
    private int maxDurationSeconds = 300; // 5 minutes max
    private int maxActiveAnnouncements = 500;
    private int maxAnnouncementsPerIp = 20;
    private int maxRepeatCount = 100;

    public boolean isHttpEnabled() {
        return httpEnabled;
    }

    public void setHttpEnabled(boolean httpEnabled) {
        this.httpEnabled = httpEnabled;
    }

    @Property(name = "netann.audio.http-enabled")
    public void setNestedHttpEnabled(boolean httpEnabled) {
        this.httpEnabled = httpEnabled;
    }

    public int getHttpConnectTimeoutMs() {
        return httpConnectTimeoutMs;
    }

    public void setHttpConnectTimeoutMs(int httpConnectTimeoutMs) {
        this.httpConnectTimeoutMs = httpConnectTimeoutMs;
    }

    @Property(name = "netann.audio.http-connect-timeout-ms")
    public void setNestedHttpConnectTimeoutMs(int httpConnectTimeoutMs) {
        this.httpConnectTimeoutMs = httpConnectTimeoutMs;
    }

    public int getHttpReadTimeoutMs() {
        return httpReadTimeoutMs;
    }

    public void setHttpReadTimeoutMs(int httpReadTimeoutMs) {
        this.httpReadTimeoutMs = httpReadTimeoutMs;
    }

    @Property(name = "netann.audio.http-read-timeout-ms")
    public void setNestedHttpReadTimeoutMs(int httpReadTimeoutMs) {
        this.httpReadTimeoutMs = httpReadTimeoutMs;
    }

    public int getMaxAudioSizeBytes() {
        return maxAudioSizeBytes;
    }

    public void setMaxAudioSizeBytes(int maxAudioSizeBytes) {
        this.maxAudioSizeBytes = maxAudioSizeBytes;
    }

    @Property(name = "netann.audio.max-audio-size-bytes")
    public void setNestedMaxAudioSizeBytes(int maxAudioSizeBytes) {
        this.maxAudioSizeBytes = maxAudioSizeBytes;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts != null ? allowedHosts : new ArrayList<>();
    }

    @Property(name = "netann.audio.allowed-hosts")
    public void setNestedAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts != null ? allowedHosts : new ArrayList<>();
    }

    public boolean isSsrfProtectionEnabled() {
        return ssrfProtectionEnabled;
    }

    public void setSsrfProtectionEnabled(boolean ssrfProtectionEnabled) {
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
    }

    @Property(name = "netann.audio.ssrf-protection-enabled")
    public void setNestedSsrfProtectionEnabled(boolean ssrfProtectionEnabled) {
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
    }

    public String getPromptDirectory() {
        return promptDirectory;
    }

    public void setPromptDirectory(String promptDirectory) {
        this.promptDirectory = promptDirectory;
    }

    @Property(name = "netann.audio.prompt-directory")
    public void setNestedPromptDirectory(String promptDirectory) {
        this.promptDirectory = promptDirectory;
    }

    public int getMaxDurationSeconds() {
        return maxDurationSeconds;
    }

    public void setMaxDurationSeconds(int maxDurationSeconds) {
        this.maxDurationSeconds = maxDurationSeconds;
    }

    @Property(name = "netann.call.max-duration-seconds")
    public void setNestedMaxDurationSeconds(int maxDurationSeconds) {
        this.maxDurationSeconds = maxDurationSeconds;
    }

    public int getMaxActiveAnnouncements() {
        return maxActiveAnnouncements;
    }

    public void setMaxActiveAnnouncements(int maxActiveAnnouncements) {
        this.maxActiveAnnouncements = maxActiveAnnouncements;
    }

    @Property(name = "netann.call.max-active-announcements")
    public void setNestedMaxActiveAnnouncements(int maxActiveAnnouncements) {
        this.maxActiveAnnouncements = maxActiveAnnouncements;
    }

    public int getMaxAnnouncementsPerIp() {
        return maxAnnouncementsPerIp;
    }

    public void setMaxAnnouncementsPerIp(int maxAnnouncementsPerIp) {
        this.maxAnnouncementsPerIp = maxAnnouncementsPerIp;
    }

    @Property(name = "netann.call.max-announcements-per-ip")
    public void setNestedMaxAnnouncementsPerIp(int maxAnnouncementsPerIp) {
        this.maxAnnouncementsPerIp = maxAnnouncementsPerIp;
    }

    public int getMaxRepeatCount() {
        return maxRepeatCount;
    }

    public void setMaxRepeatCount(int maxRepeatCount) {
        this.maxRepeatCount = maxRepeatCount;
    }

    @Property(name = "netann.call.max-repeat-count")
    public void setNestedMaxRepeatCount(int maxRepeatCount) {
        this.maxRepeatCount = maxRepeatCount;
    }
}
