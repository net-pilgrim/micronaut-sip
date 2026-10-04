package net.pilgrim.sip.model;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Base abstract class for SIP requests and responses (RFC 3261).
 */
public abstract class SipMessage {

    public static final String SIP_VERSION_2_0 = "SIP/2.0";

    protected String sipVersion = SIP_VERSION_2_0;
    protected SipHeaders headers = new SipHeaders();
    protected byte[] body = new byte[0];
    protected InetSocketAddress remoteAddress;
    protected SipTransport transport = SipTransport.UDP;

    public SipTransport getTransport() {
        return transport;
    }

    public void setTransport(SipTransport transport) {
        this.transport = (transport != null) ? transport : SipTransport.UDP;
    }

    public String getSipVersion() {
        return sipVersion;
    }

    public void setSipVersion(String sipVersion) {
        this.sipVersion = sipVersion;
    }

    public SipHeaders getHeaders() {
        return headers;
    }

    public void setHeaders(SipHeaders headers) {
        this.headers = (headers != null) ? headers : new SipHeaders();
    }

    public byte[] getBody() {
        return body;
    }

    public String getBodyAsString() {
        return getBodyAsString(StandardCharsets.UTF_8);
    }

    public String getBodyAsString(Charset charset) {
        return (body != null && body.length > 0) ? new String(body, charset) : "";
    }

    public void setBody(byte[] body) {
        this.body = (body != null) ? body : new byte[0];
        headers.setContentLength(this.body.length);
    }

    public void setBody(String body) {
        setBody(body, StandardCharsets.UTF_8);
    }

    public void setBody(String body, Charset charset) {
        if (body == null || body.isEmpty()) {
            setBody(new byte[0]);
        } else {
            setBody(body.getBytes(charset));
        }
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public void setRemoteAddress(InetSocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    // Convenience header accessors
    public String getCallId() {
        return headers.getCallId();
    }

    public void setCallId(String callId) {
        headers.setCallId(callId);
    }

    public String getFrom() {
        return headers.getFrom();
    }

    public void setFrom(String from) {
        headers.setFrom(from);
    }

    public String getTo() {
        return headers.getTo();
    }

    public void setTo(String to) {
        headers.setTo(to);
    }

    public String getVia() {
        return headers.getVia();
    }

    public String getCSeq() {
        return headers.getCSeq();
    }

    public void setCSeq(String cseq) {
        headers.setCSeq(cseq);
    }

    public String getContact() {
        return headers.getContact();
    }

    public void setContact(String contact) {
        headers.setContact(contact);
    }

    public String getContentType() {
        return headers.getContentType();
    }

    public int getContentLength() {
        return headers.getContentLength();
    }

    /**
     * Checks if this SIP message carries a DTMF signal (RFC 2976 / RFC 6086 / RFC 3428).
     */
    public boolean isDtmf() {
        String ct = getContentType();
        if (ct != null) {
            String lower = ct.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("application/dtmf-relay") || lower.contains("application/dtmf")) {
                return true;
            }
        }
        String infoPkg = headers.get(SipHeaders.INFO_PACKAGE);
        if (infoPkg != null && infoPkg.toLowerCase(java.util.Locale.ROOT).contains("dtmf")) {
            return true;
        }
        if (body != null && body.length > 0) {
            return net.pilgrim.sip.dtmf.DtmfSignal.tryParse(getBodyAsString(), ct).isPresent();
        }
        return false;
    }

    /**
     * Parses and returns the DTMF signal from this message, if present.
     */
    public java.util.Optional<net.pilgrim.sip.dtmf.DtmfSignal> getDtmfSignal() {
        if (body == null || body.length == 0) {
            return java.util.Optional.empty();
        }
        return net.pilgrim.sip.dtmf.DtmfSignal.tryParse(getBodyAsString(), getContentType());
    }

    /**
     * Sets the DTMF signal in this message body with standard application/dtmf-relay content type
     * (RFC 2976 / RFC 6086).
     */
    public void setDtmf(net.pilgrim.sip.dtmf.DtmfSignal signal) {
        setDtmf(signal, SipHeaders.APPLICATION_DTMF_RELAY);
    }

    /**
     * Sets the DTMF signal in this message body with the specified content type
     * (e.g. application/dtmf-relay or application/dtmf).
     */
    public void setDtmf(net.pilgrim.sip.dtmf.DtmfSignal signal, String contentType) {
        if (signal == null) {
            throw new IllegalArgumentException("DtmfSignal cannot be null");
        }
        String targetCt = (contentType != null && !contentType.isBlank()) ? contentType : SipHeaders.APPLICATION_DTMF_RELAY;
        headers.setContentType(targetCt);
        if (targetCt.toLowerCase(java.util.Locale.ROOT).contains("application/dtmf-relay")) {
            setBody(signal.toRelayBody());
        } else {
            setBody(signal.toDtmfBody());
        }
    }

    public abstract boolean isRequest();

    public boolean isResponse() {
        return !isRequest();
    }
}
