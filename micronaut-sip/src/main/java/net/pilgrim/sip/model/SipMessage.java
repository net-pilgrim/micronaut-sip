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

    public abstract boolean isRequest();

    public boolean isResponse() {
        return !isRequest();
    }
}
