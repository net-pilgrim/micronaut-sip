package net.pilgrim.sip.bdd.model;

import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;

import java.net.InetSocketAddress;
import java.time.Instant;

/**
 * An immutable record of a SIP message either sent or received by a VirtualUserAgent.
 */
public record RecordedMessage(
        Direction direction,
        String actorName,
        String peerName,
        InetSocketAddress peerAddress,
        SipMessage message,
        Instant timestamp
) {

    public enum Direction {
        SENT,
        RECEIVED
    }

    public String summary() {
        if (message instanceof SipRequest req) {
            return req.getMethod().name() + " " + req.getUri();
        } else if (message instanceof SipResponse resp) {
            String cseq = resp.getCSeq();
            String cseqMethod = (cseq != null && cseq.trim().split("\\s+").length > 1) ? cseq.trim().split("\\s+")[1] : null;
            return resp.getStatusCode() + " " + resp.getReasonPhrase() + (cseqMethod != null ? " (" + cseqMethod + ")" : "");
        }
        return "SIP Message";
    }

    public String callId() {
        return message != null ? message.getCallId() : null;
    }
}
