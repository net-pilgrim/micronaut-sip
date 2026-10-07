package net.pilgrim.sip.model;

/**
 * Transport protocol used for SIP communication (RFC 3261).
 */
public enum SipTransport {
    UDP("SIP/2.0/UDP", false, false, null, "sip"),
    TCP("SIP/2.0/TCP", true, false, "tcp", "sip"),
    TLS("SIP/2.0/TLS", true, true, "tls", "sips"),
    WS("SIP/2.0/WS", true, false, "ws", "sip"),
    WSS("SIP/2.0/WSS", true, true, "wss", "sips");

    private final String viaProtocol;
    private final boolean reliable;
    private final boolean secure;
    private final String uriParameter;
    private final String uriScheme;

    SipTransport(String viaProtocol, boolean reliable, boolean secure, String uriParameter, String uriScheme) {
        this.viaProtocol = viaProtocol;
        this.reliable = reliable;
        this.secure = secure;
        this.uriParameter = uriParameter;
        this.uriScheme = uriScheme;
    }

    public String getViaProtocol() {
        return viaProtocol;
    }

    public boolean isReliable() {
        return reliable;
    }

    public boolean isSecure() {
        return secure;
    }

    public String getUriParameter() {
        return uriParameter;
    }

    public String getUriScheme() {
        return uriScheme;
    }

    public static SipTransport fromVia(String viaHeader) {
        if (viaHeader == null) return UDP;
        String upper = viaHeader.toUpperCase();
        if (upper.startsWith("SIP/2.0/TLS")) return TLS;
        if (upper.startsWith("SIP/2.0/TCP")) return TCP;
        if (upper.startsWith("SIP/2.0/WSS")) return WSS;
        if (upper.startsWith("SIP/2.0/WS")) return WS;
        return UDP;
    }
}
