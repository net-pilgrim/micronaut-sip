package net.pilgrim.sip.session;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents an active SIP session or dialog context.
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
    private State state = State.INITIAL;
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
        return state;
    }

    public void setState(State state) {
        if (this.state == State.TERMINATED && state != State.TERMINATED) {
            // RFC 3261 §12.3: Once terminated, a dialog cannot transition back to any active state
            return;
        }
        this.state = state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setAttribute(String key, Object value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
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
