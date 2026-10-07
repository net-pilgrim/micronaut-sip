package net.pilgrim.sip.session;

import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.session.state.*;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents an active SIP session or dialog context governed by a Harel Statechart.
 */
public class SipSession {

    public enum State {
        INITIAL,
        EARLY,
        CONFIRMED,
        TERMINATED
    }

    private final String callId;
    private String localTag;
    private String remoteTag;
    private InetSocketAddress remoteAddress;

    // Harel Statechart Core State & Orthogonal Regions
    private volatile SipDialogState dialogState = new InitialDialogState();
    private final SdpOfferAnswerContext offerAnswerContext = new SdpOfferAnswerContext();
    private final ReliableProvisionalContext reliableContext = new ReliableProvisionalContext();

    private final Instant createdAt = Instant.now();
    private volatile Instant lastAccessedAt = Instant.now();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public SipSession(String callId) {
        this.callId = callId;
    }

    public SipSession(String callId, InetSocketAddress remoteAddress) {
        this.callId = callId;
        this.remoteAddress = remoteAddress;
    }

    public String getCallId() {
        return callId;
    }

    public String getLocalTag() {
        return localTag;
    }

    public void setLocalTag(String localTag) {
        this.localTag = localTag;
    }

    public String getRemoteTag() {
        return remoteTag;
    }

    public void setRemoteTag(String remoteTag) {
        this.remoteTag = remoteTag;
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public void setRemoteAddress(InetSocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public State getState() {
        return dialogState.getId();
    }

    public SipDialogState getDialogState() {
        return dialogState;
    }

    public void transitionTo(SipDialogState newState) {
        if (this.dialogState.isTerminal() && !newState.isTerminal()) {
            // RFC 3261 §12.3: Once terminated, a dialog cannot transition back to any active state
            return;
        }
        this.dialogState = newState;
        touch();
    }

    public void setState(State state) {
        if (this.dialogState.isTerminal() && state != State.TERMINATED) {
            // RFC 3261 §12.3: Once terminated, a dialog cannot transition back to any active state
            return;
        }
        switch (state) {
            case INITIAL -> transitionTo(new InitialDialogState());
            case EARLY -> transitionTo(new EarlyDialogState());
            case CONFIRMED -> transitionTo(new ConfirmedDialogState());
            case TERMINATED -> transitionTo(new TerminatedDialogState());
        }
    }

    // ==========================================
    // Statechart Event Handlers
    // ==========================================

    public DialogTransitionResult handleInvite(SipRequest request, String sdpOffer) {
        touch();
        return dialogState.handleInvite(this, request, sdpOffer);
    }

    public DialogTransitionResult handleProvisional(SipResponse response) {
        touch();
        return dialogState.handleProvisional(this, response);
    }

    public DialogTransitionResult handleAck(SipRequest request, String sdpAnswer) {
        touch();
        return dialogState.handleAck(this, request, sdpAnswer);
    }

    public DialogTransitionResult handlePrack(SipRequest request, String rack, String prackBody) {
        touch();
        return dialogState.handlePrack(this, request, rack, prackBody);
    }

    public DialogTransitionResult handleBye(SipRequest request) {
        touch();
        return dialogState.handleBye(this, request);
    }

    public DialogTransitionResult handleCancel(SipRequest request) {
        touch();
        return dialogState.handleCancel(this, request);
    }

    public DialogTransitionResult handleTimeout(String reason) {
        touch();
        return dialogState.handleTimeout(this, reason);
    }

    public SdpOfferAnswerContext getOfferAnswerContext() {
        return offerAnswerContext;
    }

    public ReliableProvisionalContext getReliableContext() {
        return reliableContext;
    }

    public boolean isConfirmed() {
        return getState() == State.CONFIRMED;
    }

    public boolean isEarly() {
        return getState() == State.EARLY;
    }

    public boolean isTerminated() {
        return getState() == State.TERMINATED;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setAttribute(String key, Object value) {
        if (value == null) {
            attributes.remove(key);
            return;
        }
        attributes.put(key, value);
        // Synchronize with typed orthogonal contexts
        if ("sdpOffer".equals(key) && value instanceof String sdp) {
            offerAnswerContext.setOffer(sdp);
        } else if ("sdpAnswer".equals(key) && value instanceof String sdp) {
            offerAnswerContext.setAnswer(sdp);
        } else if ("localSdpOffer".equals(key) && value instanceof String sdp) {
            offerAnswerContext.setLocalOffer(sdp);
        } else if ("awaitingAckSdpAnswer".equals(key) && Boolean.TRUE.equals(value)) {
            offerAnswerContext.setState(SdpOfferAnswerState.AWAITING_ACK_ANSWER);
        } else if ("rseq".equals(key) && value instanceof Long r) {
            reliableContext.initiate(r, 1L, "INVITE", (reactor.core.publisher.Sinks.One<Void>) attributes.get("prackSink"));
        } else if ("prackSink".equals(key) && value instanceof reactor.core.publisher.Sinks.One sink) {
            Long r = reliableContext.getRSeq() != null ? reliableContext.getRSeq() : (Long) attributes.get("rseq");
            reliableContext.initiate(r != null ? r : 1L, 1L, "INVITE", (reactor.core.publisher.Sinks.One<Void>) sink);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        if ("sdpOffer".equals(key)) {
            String offer = offerAnswerContext.getOffer();
            if (offer != null) return (T) offer;
        } else if ("sdpAnswer".equals(key)) {
            String answer = offerAnswerContext.getAnswer();
            if (answer != null) return (T) answer;
        } else if ("awaitingAckSdpAnswer".equals(key)) {
            return (T) Boolean.valueOf(offerAnswerContext.isAwaitingAckAnswer());
        } else if ("rseq".equals(key)) {
            Long rseq = reliableContext.getRSeq();
            if (rseq != null) return (T) rseq;
        } else if ("prackSink".equals(key)) {
            Object sink = reliableContext.getPrackSink();
            if (sink != null) return (T) sink;
        }
        return (T) attributes.get(key);
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void touch() {
        this.lastAccessedAt = Instant.now();
    }

    public boolean isExpired(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return false;
        }
        return Instant.now().isAfter(lastAccessedAt.plus(ttl));
    }
}
