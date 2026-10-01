package net.pilgrim.sip.model;

/**
 * Transport protocol used for SIP communication (RFC 3261).
 */
public enum SipTransport {
    UDP("SIP/2.0/UDP"),
    TCP("SIP/2.0/TCP"),
    TLS("SIP/2.0/TLS"),
    WS("SIP/2.0/WS"),
    WSS("SIP/2.0/WSS");

    private final String viaProtocol;

    SipTransport(String viaProtocol) {
        this.viaProtocol = viaProtocol;
    }

    public String getViaProtocol() {
        return viaProtocol;
    }

    public static SipTransport fromVia(String viaHeader) {
        if (viaHeader == null) return UDP;
        String upper = viaHeader.toUpperCase();
        if (upper.startsWith("SIP/2.0/TCP")) return TCP;
        if (upper.startsWith("SIP/2.0/TLS")) return TLS;
        if (upper.startsWith("SIP/2.0/WSS")) return WSS;
        if (upper.startsWith("SIP/2.0/WS")) return WS;
        return UDP;
    }
}
