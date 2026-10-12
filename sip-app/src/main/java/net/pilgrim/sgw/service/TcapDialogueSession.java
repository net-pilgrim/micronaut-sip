package net.pilgrim.sgw.service;

import net.pilgrim.sgw.model.TcapMessage;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stateful TCAP dialogue session tracking transaction IDs (otid / dtid) and dialogue state machine.
 */
public class TcapDialogueSession {

    public enum State {
        IDLE,
        ACTIVE,
        TERMINATED,
        ABORTED
    }

    private final String dialogueId;
    private final String sipCallId;
    private final String clientOtid;
    private final String serverDtid;
    private volatile State state = State.ACTIVE;
    private volatile String applicationContext;
    private volatile InetSocketAddress remoteAddress;
    private volatile SipRequest originalInvite;
    private volatile SipResponse originalOk;
    private final Instant createdAt = Instant.now();
    private volatile Instant lastAccessedAt = Instant.now();
    private final AtomicLong serverCSeq = new AtomicLong(10);
    private final List<TcapMessage> history = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public TcapDialogueSession(String dialogueId,
                               String sipCallId,
                               String clientOtid,
                               String applicationContext,
                               InetSocketAddress remoteAddress,
                               SipRequest originalInvite,
                               SipResponse originalOk) {
        this.dialogueId = dialogueId;
        this.serverDtid = dialogueId;
        this.sipCallId = sipCallId;
        this.clientOtid = clientOtid;
        this.applicationContext = applicationContext;
        this.remoteAddress = remoteAddress;
        this.originalInvite = originalInvite;
        this.originalOk = originalOk;
    }

    public String getDialogueId() {
        return dialogueId;
    }

    public String getSipCallId() {
        return sipCallId;
    }

    public String getClientOtid() {
        return clientOtid;
    }

    public String getServerDtid() {
        return serverDtid;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
        touch();
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    public boolean isTerminated() {
        return state == State.TERMINATED || state == State.ABORTED;
    }

    public String getApplicationContext() {
        return applicationContext;
    }

    public void setApplicationContext(String applicationContext) {
        this.applicationContext = applicationContext;
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public void setRemoteAddress(InetSocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public SipRequest getOriginalInvite() {
        return originalInvite;
    }

    public void setOriginalInvite(SipRequest originalInvite) {
        this.originalInvite = originalInvite;
    }

    public SipResponse getOriginalOk() {
        return originalOk;
    }

    public void setOriginalOk(SipResponse originalOk) {
        this.originalOk = originalOk;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void touch() {
        this.lastAccessedAt = Instant.now();
    }

    public long nextServerCSeq() {
        return serverCSeq.incrementAndGet();
    }

    public void recordMessage(TcapMessage message) {
        if (message != null) {
            history.add(message);
            touch();
        }
    }

    public List<TcapMessage> getHistory() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        } else {
            attributes.remove(key);
        }
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public String toString() {
        return "TcapDialogueSession{" +
                "dialogueId='" + dialogueId + '\'' +
                ", sipCallId='" + sipCallId + '\'' +
                ", clientOtid='" + clientOtid + '\'' +
                ", serverDtid='" + serverDtid + '\'' +
                ", state=" + state +
                ", historyCount=" + history.size() +
                '}';
    }
}
