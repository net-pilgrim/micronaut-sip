package net.pilgrim.sip.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Property;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("sip.server")
public class SipServerConfiguration {

    private String udpHost = "0.0.0.0";
    private String tcpHost = "0.0.0.0";
    private int udpPort = 5060;
    private String advertisedIp;
    private boolean enabled = true;
    private boolean udpEnabled = true;
    private boolean tcpEnabled = true;
    private int tcpPort = 5060;
    private String serverName = "Micronaut-SIP/1.0";
    private long requestTimeoutMs = 5000;
    private long sessionTtlMs = 1800000; // 30 minutes default
    private int maxSessions = 10000;
    private boolean auto100TryingEnabled = true;
    private long auto100TryingDelayMs = 200;
    private boolean autoCancelEnabled = true;
    private int maxMessageSizeBytes = 65536; // 64 KB default
    private int maxHeaderCount = 100;
    private int maxHeaderSizeBytes = 8192; // 8 KB default

    // Rate Limiting & Anti-Flood Protection
    private boolean rateLimitEnabled = false;
    private double rateLimitRequestsPerSecond = 100.0;
    private int rateLimitBurstCapacity = 200;
    private int rateLimitRetryAfterSeconds = 5;
    private List<String> rateLimitWhitelist = new ArrayList<>();
    private int rateLimitMaxTrackedIps = 10000;

    // Access Logging
    private boolean accessLogEnabled = false;

    // MDC Tracing
    private boolean mdcEnabled = true;

    // RFC 3261 SIP Timers (in milliseconds)
    private long t1Ms = 500;
    private long t2Ms = 4000;
    private long t4Ms = 5000;
    private long timerBDelayMs = 32000; // 64 * T1 (INVITE client transaction)
    private long timerFDelayMs = 32000; // 64 * T1 (Non-INVITE client transaction)
    private long timerHDelayMs = 32000; // 64 * T1 (UAS wait for ACK)
    private long timerJDelayMs = 32000; // 64 * T1 (UAS non-INVITE response replay cache)
    private long ringTimeoutMs = 180000; // 180s ring timeout (post-provisional alerting)
    private boolean clientRetransmitEnabled = true;
    private boolean uas2xxRetransmitEnabled = true;
    private boolean uas100relRetransmitEnabled = true;

    public String getUdpHost() {
        return udpHost;
    }

    public void setUdpHost(String udpHost) {
        this.udpHost = udpHost;
    }

    /**
     * Orthogonal UDP host key (sip.server.udp.host).
     */
    @Property(name = "sip.server.udp.host")
    public void setUdpNestedHost(String udpHost) {
        this.udpHost = udpHost;
    }

    public String getAdvertisedIp() {
        return advertisedIp;
    }

    public void setAdvertisedIp(String advertisedIp) {
        this.advertisedIp = advertisedIp;
    }

    @Property(name = "sip.server.advertised-ip")
    public void setAdvertisedNestedIp(String advertisedIp) {
        this.advertisedIp = advertisedIp;
    }

    /**
     * Resolves the IP address to advertise in SIP Contact and SDP c=/o= lines.
     * Hierarchy:
     * 1. Explicitly configured sip.server.advertised-ip (or SIP_SERVER_ADVERTISED_IP env).
     * 2. sip.server.udp.host if not "0.0.0.0".
     * 3. sip.server.tcp.host if not "0.0.0.0".
     * 4. Auto-detected host IP (if not loopback/any-local).
     * 5. "127.0.0.1" fallback.
     */
    public String resolveAdvertisedIp() {
        if (advertisedIp != null && !advertisedIp.isBlank()) {
            return advertisedIp.trim();
        }
        if (udpHost != null && !udpHost.isBlank() && !"0.0.0.0".equals(udpHost)) {
            return udpHost.trim();
        }
        if (tcpHost != null && !tcpHost.isBlank() && !"0.0.0.0".equals(tcpHost)) {
            return tcpHost.trim();
        }
        try {
            java.net.InetAddress localHost = java.net.InetAddress.getLocalHost();
            if (localHost != null && !localHost.isLoopbackAddress() && !localHost.isAnyLocalAddress()) {
                return localHost.getHostAddress();
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    public String getTcpHost() {
        return tcpHost;
    }

    public void setTcpHost(String tcpHost) {
        this.tcpHost = tcpHost;
    }

    /**
     * Orthogonal TCP host key (sip.server.tcp.host).
     */
    @Property(name = "sip.server.tcp.host")
    public void setTcpNestedHost(String tcpHost) {
        this.tcpHost = tcpHost;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public void setUdpPort(int udpPort) {
        this.udpPort = udpPort;
    }

    /**
     * Orthogonal UDP port key (sip.server.udp.port).
     */
    @Property(name = "sip.server.udp.port")
    public void setUdpNestedPort(int udpPort) {
        this.udpPort = udpPort;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isUdpEnabled() {
        return udpEnabled;
    }

    public void setUdpEnabled(boolean udpEnabled) {
        this.udpEnabled = udpEnabled;
    }

    public boolean isTcpEnabled() {
        return tcpEnabled;
    }

    public void setTcpEnabled(boolean tcpEnabled) {
        this.tcpEnabled = tcpEnabled;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public void setTcpPort(int tcpPort) {
        this.tcpPort = tcpPort;
    }

    /**
     * Orthogonal TCP port key (sip.server.tcp.port).
     */
    @Property(name = "sip.server.tcp.port")
    public void setTcpNestedPort(int tcpPort) {
        this.tcpPort = tcpPort;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
        this.timerBDelayMs = requestTimeoutMs;
        this.timerFDelayMs = requestTimeoutMs;
    }

    public long getSessionTtlMs() {
        return sessionTtlMs;
    }

    public void setSessionTtlMs(long sessionTtlMs) {
        this.sessionTtlMs = sessionTtlMs;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public boolean isAuto100TryingEnabled() {
        return auto100TryingEnabled;
    }

    public void setAuto100TryingEnabled(boolean auto100TryingEnabled) {
        this.auto100TryingEnabled = auto100TryingEnabled;
    }

    public long getAuto100TryingDelayMs() {
        return auto100TryingDelayMs;
    }

    public void setAuto100TryingDelayMs(long auto100TryingDelayMs) {
        this.auto100TryingDelayMs = auto100TryingDelayMs;
    }

    public int getMaxMessageSizeBytes() {
        return maxMessageSizeBytes;
    }

    public void setMaxMessageSizeBytes(int maxMessageSizeBytes) {
        this.maxMessageSizeBytes = maxMessageSizeBytes;
    }

    public int getMaxHeaderCount() {
        return maxHeaderCount;
    }

    public void setMaxHeaderCount(int maxHeaderCount) {
        this.maxHeaderCount = maxHeaderCount;
    }

    public int getMaxHeaderSizeBytes() {
        return maxHeaderSizeBytes;
    }

    public void setMaxHeaderSizeBytes(int maxHeaderSizeBytes) {
        this.maxHeaderSizeBytes = maxHeaderSizeBytes;
    }

    public boolean isAutoCancelEnabled() {
        return autoCancelEnabled;
    }

    public void setAutoCancelEnabled(boolean autoCancelEnabled) {
        this.autoCancelEnabled = autoCancelEnabled;
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public void setRateLimitEnabled(boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    @Property(name = "sip.server.rate-limit.enabled")
    public void setRateLimitNestedEnabled(boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    public double getRateLimitRequestsPerSecond() {
        return rateLimitRequestsPerSecond;
    }

    public void setRateLimitRequestsPerSecond(double rateLimitRequestsPerSecond) {
        this.rateLimitRequestsPerSecond = rateLimitRequestsPerSecond;
    }

    @Property(name = "sip.server.rate-limit.requests-per-second")
    public void setRateLimitNestedRequestsPerSecond(double rateLimitRequestsPerSecond) {
        this.rateLimitRequestsPerSecond = rateLimitRequestsPerSecond;
    }

    public int getRateLimitBurstCapacity() {
        return rateLimitBurstCapacity;
    }

    public void setRateLimitBurstCapacity(int rateLimitBurstCapacity) {
        this.rateLimitBurstCapacity = rateLimitBurstCapacity;
    }

    @Property(name = "sip.server.rate-limit.burst-capacity")
    public void setRateLimitNestedBurstCapacity(int rateLimitBurstCapacity) {
        this.rateLimitBurstCapacity = rateLimitBurstCapacity;
    }

    public int getRateLimitRetryAfterSeconds() {
        return rateLimitRetryAfterSeconds;
    }

    public void setRateLimitRetryAfterSeconds(int rateLimitRetryAfterSeconds) {
        this.rateLimitRetryAfterSeconds = rateLimitRetryAfterSeconds;
    }

    @Property(name = "sip.server.rate-limit.retry-after-seconds")
    public void setRateLimitNestedRetryAfterSeconds(int rateLimitRetryAfterSeconds) {
        this.rateLimitRetryAfterSeconds = rateLimitRetryAfterSeconds;
    }

    public List<String> getRateLimitWhitelist() {
        return rateLimitWhitelist;
    }

    public void setRateLimitWhitelist(List<String> rateLimitWhitelist) {
        this.rateLimitWhitelist = rateLimitWhitelist != null ? rateLimitWhitelist : new ArrayList<>();
    }

    @Property(name = "sip.server.rate-limit.whitelist")
    public void setRateLimitNestedWhitelist(List<String> rateLimitWhitelist) {
        this.rateLimitWhitelist = rateLimitWhitelist != null ? rateLimitWhitelist : new ArrayList<>();
    }

    public int getRateLimitMaxTrackedIps() {
        return rateLimitMaxTrackedIps;
    }

    public void setRateLimitMaxTrackedIps(int rateLimitMaxTrackedIps) {
        this.rateLimitMaxTrackedIps = rateLimitMaxTrackedIps;
    }

    @Property(name = "sip.server.rate-limit.max-tracked-ips")
    public void setRateLimitNestedMaxTrackedIps(int rateLimitMaxTrackedIps) {
        this.rateLimitMaxTrackedIps = rateLimitMaxTrackedIps;
    }

    public boolean isAccessLogEnabled() {
        return accessLogEnabled;
    }

    public void setAccessLogEnabled(boolean accessLogEnabled) {
        this.accessLogEnabled = accessLogEnabled;
    }

    @Property(name = "sip.server.access-log.enabled")
    public void setAccessLogNestedEnabled(boolean accessLogEnabled) {
        this.accessLogEnabled = accessLogEnabled;
    }

    public boolean isMdcEnabled() {
        return mdcEnabled;
    }

    public void setMdcEnabled(boolean mdcEnabled) {
        this.mdcEnabled = mdcEnabled;
    }

    @Property(name = "sip.server.mdc.enabled")
    public void setMdcNestedEnabled(boolean mdcEnabled) {
        this.mdcEnabled = mdcEnabled;
    }

    public long getT1Ms() {
        return t1Ms;
    }

    public void setT1Ms(long t1Ms) {
        this.t1Ms = t1Ms;
    }

    @Property(name = "sip.server.timer.t1-ms")
    public void setT1NestedMs(long t1Ms) {
        this.t1Ms = t1Ms;
    }

    public long getT2Ms() {
        return t2Ms;
    }

    public void setT2Ms(long t2Ms) {
        this.t2Ms = t2Ms;
    }

    @Property(name = "sip.server.timer.t2-ms")
    public void setT2NestedMs(long t2Ms) {
        this.t2Ms = t2Ms;
    }

    public long getT4Ms() {
        return t4Ms;
    }

    public void setT4Ms(long t4Ms) {
        this.t4Ms = t4Ms;
    }

    @Property(name = "sip.server.timer.t4-ms")
    public void setT4NestedMs(long t4Ms) {
        this.t4Ms = t4Ms;
    }

    public long getTimerBDelayMs() {
        return timerBDelayMs;
    }

    public void setTimerBDelayMs(long timerBDelayMs) {
        this.timerBDelayMs = timerBDelayMs;
    }

    @Property(name = "sip.server.timer.b-ms")
    public void setTimerBNestedDelayMs(long timerBDelayMs) {
        this.timerBDelayMs = timerBDelayMs;
    }

    public long getTimerFDelayMs() {
        return timerFDelayMs;
    }

    public void setTimerFDelayMs(long timerFDelayMs) {
        this.timerFDelayMs = timerFDelayMs;
    }

    @Property(name = "sip.server.timer.f-ms")
    public void setTimerFNestedDelayMs(long timerFDelayMs) {
        this.timerFDelayMs = timerFDelayMs;
    }

    public long getTimerHDelayMs() {
        return timerHDelayMs;
    }

    public void setTimerHDelayMs(long timerHDelayMs) {
        this.timerHDelayMs = timerHDelayMs;
    }

    @Property(name = "sip.server.timer.h-ms")
    public void setTimerHNestedDelayMs(long timerHDelayMs) {
        this.timerHDelayMs = timerHDelayMs;
    }

    public long getTimerJDelayMs() {
        return timerJDelayMs;
    }

    public void setTimerJDelayMs(long timerJDelayMs) {
        this.timerJDelayMs = timerJDelayMs;
    }

    @Property(name = "sip.server.timer.j-ms")
    public void setTimerJNestedDelayMs(long timerJDelayMs) {
        this.timerJDelayMs = timerJDelayMs;
    }

    public long getRingTimeoutMs() {
        return ringTimeoutMs;
    }

    public void setRingTimeoutMs(long ringTimeoutMs) {
        this.ringTimeoutMs = ringTimeoutMs;
    }

    @Property(name = "sip.server.timer.ring-timeout-ms")
    public void setRingTimeoutNestedMs(long ringTimeoutMs) {
        this.ringTimeoutMs = ringTimeoutMs;
    }

    public boolean isClientRetransmitEnabled() {
        return clientRetransmitEnabled;
    }

    public void setClientRetransmitEnabled(boolean clientRetransmitEnabled) {
        this.clientRetransmitEnabled = clientRetransmitEnabled;
    }

    @Property(name = "sip.server.timer.client-retransmit-enabled")
    public void setClientRetransmitNestedEnabled(boolean clientRetransmitEnabled) {
        this.clientRetransmitEnabled = clientRetransmitEnabled;
    }

    public boolean isUas2xxRetransmitEnabled() {
        return uas2xxRetransmitEnabled;
    }

    public void setUas2xxRetransmitEnabled(boolean uas2xxRetransmitEnabled) {
        this.uas2xxRetransmitEnabled = uas2xxRetransmitEnabled;
    }

    @Property(name = "sip.server.timer.uas-2xx-retransmit-enabled")
    public void setUas2xxRetransmitNestedEnabled(boolean uas2xxRetransmitEnabled) {
        this.uas2xxRetransmitEnabled = uas2xxRetransmitEnabled;
    }

    public boolean isUas100relRetransmitEnabled() {
        return uas100relRetransmitEnabled;
    }

    public void setUas100relRetransmitEnabled(boolean uas100relRetransmitEnabled) {
        this.uas100relRetransmitEnabled = uas100relRetransmitEnabled;
    }

    @Property(name = "sip.server.timer.uas-100rel-retransmit-enabled")
    public void setUas100relRetransmitNestedEnabled(boolean uas100relRetransmitEnabled) {
        this.uas100relRetransmitEnabled = uas100relRetransmitEnabled;
    }
}
