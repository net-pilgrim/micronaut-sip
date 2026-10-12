package net.pilgrim.sgw.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuration properties for the Dummy Signalling Gateway (TCAP over SIP).
 */
@ConfigurationProperties("sgw")
public class SignallingGatewayConfiguration {

    public enum DeliveryMode {
        RESPONSE_BODY,
        SERVER_INFO,
        BOTH
    }

    private boolean enabled = true;
    private String uriPrefix = "sgw";
    private DeliveryMode deliveryMode = DeliveryMode.BOTH;
    private long dialogueTtlSeconds = 300;
    private String defaultApplicationContext = "dummy-map-v3";
    private String advertisedIp;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUriPrefix() {
        return uriPrefix;
    }

    public void setUriPrefix(String uriPrefix) {
        this.uriPrefix = uriPrefix;
    }

    public DeliveryMode getDeliveryMode() {
        return deliveryMode;
    }

    public void setDeliveryMode(DeliveryMode deliveryMode) {
        this.deliveryMode = deliveryMode;
    }

    public long getDialogueTtlSeconds() {
        return dialogueTtlSeconds;
    }

    public void setDialogueTtlSeconds(long dialogueTtlSeconds) {
        this.dialogueTtlSeconds = dialogueTtlSeconds;
    }

    public String getDefaultApplicationContext() {
        return defaultApplicationContext;
    }

    public void setDefaultApplicationContext(String defaultApplicationContext) {
        this.defaultApplicationContext = defaultApplicationContext;
    }

    public String getAdvertisedIp() {
        return advertisedIp;
    }

    public void setAdvertisedIp(String advertisedIp) {
        this.advertisedIp = advertisedIp;
    }
}
