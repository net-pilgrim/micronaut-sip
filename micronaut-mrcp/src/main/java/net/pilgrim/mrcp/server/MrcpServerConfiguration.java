package net.pilgrim.mrcp.server;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuration properties for the MRCPv2 TCP server.
 */
@ConfigurationProperties("mrcp.server")
public class MrcpServerConfiguration {

    private boolean enabled = true;
    private int port = 1554;
    private String bindAddress = "0.0.0.0";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        if (bindAddress != null && !bindAddress.isBlank()) {
            this.bindAddress = bindAddress.trim();
        }
    }
}
