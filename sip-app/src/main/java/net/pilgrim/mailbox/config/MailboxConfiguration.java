package net.pilgrim.mailbox.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuration for the SIP answering machine application.
 */
@ConfigurationProperties("mailbox")
public class MailboxConfiguration {

    private boolean enabled = true;
    private String user = "mailbox";
    private int maxRecordingSeconds = 30;
    private char finishDtmf = '#';
    private int beepFrequencyHz = 1000;
    private int beepDurationMs = 500;
    private String storagePrefix = "";
    private int maxActiveCalls = 1000;
    private int maxCallsPerIp = 500;
    private String advertisedIp;
    private String promptVxml = "classpath:vxml/voicemail_app.vxml";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user != null ? user : "mailbox";
    }

    public int getMaxRecordingSeconds() {
        return maxRecordingSeconds;
    }

    public void setMaxRecordingSeconds(int maxRecordingSeconds) {
        this.maxRecordingSeconds = maxRecordingSeconds > 0 ? maxRecordingSeconds : 30;
    }

    public char getFinishDtmf() {
        return finishDtmf;
    }

    public void setFinishDtmf(char finishDtmf) {
        this.finishDtmf = finishDtmf;
    }

    public int getBeepFrequencyHz() {
        return beepFrequencyHz;
    }

    public void setBeepFrequencyHz(int beepFrequencyHz) {
        this.beepFrequencyHz = beepFrequencyHz;
    }

    public int getBeepDurationMs() {
        return beepDurationMs;
    }

    public void setBeepDurationMs(int beepDurationMs) {
        this.beepDurationMs = beepDurationMs;
    }

    public String getStoragePrefix() {
        return storagePrefix;
    }

    public void setStoragePrefix(String storagePrefix) {
        this.storagePrefix = storagePrefix != null ? storagePrefix : "";
    }

    public String getPromptVxml() {
        return promptVxml;
    }

    public void setPromptVxml(String promptVxml) {
        this.promptVxml = promptVxml;
    }

    public int getMaxActiveCalls() {
        return maxActiveCalls;
    }

    public void setMaxActiveCalls(int maxActiveCalls) {
        this.maxActiveCalls = maxActiveCalls > 0 ? maxActiveCalls : 50;
    }

    public int getMaxCallsPerIp() {
        return maxCallsPerIp;
    }

    public void setMaxCallsPerIp(int maxCallsPerIp) {
        this.maxCallsPerIp = maxCallsPerIp > 0 ? maxCallsPerIp : 10;
    }

    public String getAdvertisedIp() {
        return advertisedIp;
    }

    public void setAdvertisedIp(String advertisedIp) {
        this.advertisedIp = advertisedIp;
    }
}
