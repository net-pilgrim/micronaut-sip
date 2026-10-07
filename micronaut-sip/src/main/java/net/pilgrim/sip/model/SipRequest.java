package net.pilgrim.sip.model;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Models a SIP Request according to RFC 3261.
 */
public class SipRequest extends SipMessage {

    private SipMethod method;
    private String customMethod;
    private SipUri uri;

    public SipRequest() {}

    public SipRequest(SipMethod method, SipUri uri) {
        this.method = method;
        this.uri = uri;
    }

    public SipRequest(SipMethod method, String uri) {
        this.method = method;
        this.uri = SipUri.parse(uri);
    }

    @Override
    public boolean isRequest() {
        return true;
    }

    public SipMethod getMethod() {
        return method;
    }

    public void setMethod(SipMethod method) {
        this.method = method;
    }

    public String getCustomMethod() {
        return customMethod;
    }

    public void setCustomMethod(String customMethod) {
        this.customMethod = customMethod;
    }

    public String getMethodName() {
        if (customMethod != null && !customMethod.isEmpty()) {
            return customMethod;
        }
        return method != null ? method.name() : "UNKNOWN";
    }

    public SipUri getUri() {
        return uri;
    }

    public void setUri(SipUri uri) {
        this.uri = uri;
    }

    /**
     * Creates an RFC 3261 compliant response to this request.
     * Copies Via, From, To (adding tag if needed), Call-ID, CSeq.
     */
    public SipResponse createResponse(int statusCode) {
        return createResponse(statusCode, SipStatus.getReasonPhrase(statusCode));
    }

    /**
     * Processes NAT traversal and spoofing mitigation on the topmost Via header
     * per RFC 3261 Section 18.2.1 and RFC 3581 Section 4.
     * Inserts 'received' parameter if sender IP differs from sent-by host,
     * and sets 'rport' parameter value to sender port if client requested 'rport'.
     */
    public void processNatVia(InetSocketAddress sender) {
        if (sender == null) return;
        String topViaStr = headers.getVia();
        if (topViaStr == null || topViaStr.trim().isEmpty()) return;

        try {
            ViaHeader topVia = ViaHeader.parse(topViaStr);
            String senderIp = (sender.getAddress() != null) ? sender.getAddress().getHostAddress() : sender.getHostString();
            String sentByHost = topVia.getHost();

            boolean modified = false;
            boolean hostMatches = sentByHost != null && (sentByHost.equalsIgnoreCase(senderIp) || sentByHost.equalsIgnoreCase(sender.getHostString()));

            // RFC 3261 §18.2.1: If sent-by host doesn't match received IP, add received parameter
            if (!hostMatches) {
                if (!senderIp.equals(topVia.getReceived())) {
                    topVia.setReceived(senderIp);
                    modified = true;
                }
            }

            // RFC 3581 §4: If rport parameter is present, set its value to sender's source port
            // and ensure received parameter is added even if sent-by host matches.
            if (topVia.hasRport()) {
                if (topVia.getRport() != sender.getPort()) {
                    topVia.setRport(sender.getPort());
                    modified = true;
                }
                if (topVia.getReceived() == null) {
                    topVia.setReceived(senderIp);
                    modified = true;
                }
            }

            if (modified) {
                headers.setTopVia(topVia.toString());
            }
        } catch (Exception ignored) {
            // Ignore malformed via here; parser or validator will handle invalid syntax
        }
    }

    /**
     * Creates an RFC 3261 compliant response to this request.
     */
    public SipResponse createResponse(int statusCode, String reasonPhrase) {
        SipResponse response = new SipResponse(statusCode, reasonPhrase);
        response.setSipVersion(this.sipVersion);
        response.setTransport(this.transport);

        // Resolve response destination per RFC 3261 §18.2.2 / RFC 3581 §4
        String topViaStr = this.headers.getVia();
        if (topViaStr != null && !topViaStr.trim().isEmpty()) {
            try {
                ViaHeader topVia = ViaHeader.parse(topViaStr);
                response.setRemoteAddress(topVia.resolveResponseAddress(this.remoteAddress));
            } catch (Exception e) {
                response.setRemoteAddress(this.remoteAddress);
            }
        } else {
            response.setRemoteAddress(this.remoteAddress);
        }

        SipHeaders respHeaders = response.getHeaders();

        // 1. Copy all Via headers in exact order
        for (String via : this.headers.getVias()) {
            respHeaders.addVia(via);
        }

        // 2. Copy From header
        if (this.getFrom() != null) {
            respHeaders.setFrom(this.getFrom());
        }

        // 3. Copy To header (add tag if not present, unless status is 100 Trying per RFC 3261 section 8.2.6.2)
        String to = this.getTo();
        if (to != null) {
            if (statusCode != SipStatus.TRYING && !to.toLowerCase().contains("tag=")) {
                String toTag = Long.toHexString(UUID.randomUUID().getMostSignificantBits() & 0xFFFFFFFFL);
                to = to + ";tag=" + toTag;
            }
            respHeaders.setTo(to);
        }

        // 4. Copy Call-ID
        if (this.getCallId() != null) {
            respHeaders.setCallId(this.getCallId());
        }

        // 5. Copy CSeq
        if (this.getCSeq() != null) {
            respHeaders.setCSeq(this.getCSeq());
        }

        // 6. Default Content-Length: 0
        respHeaders.setContentLength(0);

        return response;
    }

    /**
     * Static builder for an outgoing SIP Request.
     */
    public static Builder builder(SipMethod method, String uri) {
        return new Builder(method, SipUri.parse(uri));
    }

    public static Builder builder(SipMethod method, SipUri uri) {
        return new Builder(method, uri);
    }

    public static class Builder {
        private final SipRequest request;

        private Builder(SipMethod method, SipUri uri) {
            this.request = new SipRequest(method, uri);
            this.request.headers.setMaxForwards(70);
        }

        public Builder from(String from) {
            this.request.headers.setFrom(from);
            return this;
        }

        public Builder to(String to) {
            this.request.headers.setTo(to);
            return this;
        }

        public Builder callId(String callId) {
            this.request.headers.setCallId(callId);
            return this;
        }

        public Builder cseq(long seqNumber, SipMethod method) {
            this.request.headers.setCSeq(seqNumber + " " + method.name());
            return this;
        }

        public Builder via(String via) {
            this.request.headers.addVia(via);
            return this;
        }

        public Builder contact(String contact) {
            this.request.headers.setContact(contact);
            return this;
        }

        public Builder contentType(String contentType) {
            this.request.headers.setContentType(contentType);
            return this;
        }

        public Builder body(String body) {
            this.request.setBody(body);
            return this;
        }

        public Builder body(String body, String contentType) {
            this.request.setBody(body);
            this.request.headers.setContentType(contentType);
            return this;
        }

        public Builder body(byte[] body) {
            this.request.setBody(body);
            return this;
        }

        public Builder transport(SipTransport transport) {
            this.request.setTransport(transport);
            return this;
        }

        public Builder header(String name, String value) {
            this.request.headers.add(name, value);
            return this;
        }

        public Builder remoteAddress(InetSocketAddress remoteAddress) {
            this.request.setRemoteAddress(remoteAddress);
            return this;
        }

        public Builder dtmf(net.pilgrim.sip.dtmf.DtmfSignal signal) {
            return dtmf(signal, SipHeaders.APPLICATION_DTMF_RELAY);
        }

        public Builder dtmf(net.pilgrim.sip.dtmf.DtmfSignal signal, String contentType) {
            this.request.setDtmf(signal, contentType);
            return this;
        }

        public Builder dtmf(char digit) {
            return dtmf(net.pilgrim.sip.dtmf.DtmfSignal.of(digit));
        }

        public Builder dtmf(char digit, int durationMs) {
            return dtmf(net.pilgrim.sip.dtmf.DtmfSignal.of(digit, durationMs));
        }

        public Builder dtmfRelay(char digit, int durationMs) {
            return dtmf(net.pilgrim.sip.dtmf.DtmfSignal.of(digit, durationMs), SipHeaders.APPLICATION_DTMF_RELAY);
        }

        public SipRequest build() {
            // Fill defaults if missing
            if (this.request.getCallId() == null) {
                this.request.headers.setCallId(UUID.randomUUID().toString() + "@" + (this.request.uri != null ? this.request.uri.getHost() : "localhost"));
            }
            if (this.request.getCSeq() == null) {
                this.request.headers.setCSeq("1 " + this.request.method.name());
            }
            return this.request;
        }
    }

    /**
     * Extracts the topmost Via header parsed as ViaHeader, or null if missing/invalid.
     */
    public ViaHeader getTopmostVia() {
        String topVia = headers.getVia();
        if (topVia == null || topVia.trim().isEmpty()) return null;
        try {
            return ViaHeader.parse(topVia);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the branch parameter from the topmost Via header, or null if absent.
     */
    public String getBranch() {
        ViaHeader top = getTopmostVia();
        return top != null ? top.getBranch() : null;
    }

    /**
     * Extracts the numeric sequence number from the CSeq header, or -1 if invalid/absent.
     */
    public long getCSeqNumber() {
        String cseq = getCSeq();
        if (cseq == null) return -1;
        int space = cseq.trim().indexOf(' ');
        String numStr = (space != -1) ? cseq.trim().substring(0, space).trim() : cseq.trim();
        try {
            return Long.parseLong(numStr);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Extracts the method name from the CSeq header, or null if invalid/absent.
     */
    public String getCSeqMethod() {
        String cseq = getCSeq();
        if (cseq == null) return null;
        int space = cseq.trim().indexOf(' ');
        if (space == -1) return null;
        return cseq.trim().substring(space + 1).trim();
    }

    @Override
    public String toString() {
        return method + " " + uri + " " + sipVersion;
    }
}
