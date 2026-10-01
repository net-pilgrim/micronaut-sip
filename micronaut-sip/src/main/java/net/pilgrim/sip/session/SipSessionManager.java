package net.pilgrim.sip.session;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.sip.config.SipServerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active SIP dialog sessions with TTL eviction and capacity capping.
 */
@Singleton
public class SipSessionManager {

    private static final Logger LOG = LoggerFactory.getLogger(SipSessionManager.class);

    private final Map<String, SipSession> sessions = new ConcurrentHashMap<>();
    private final Duration sessionTtl;
    private final int maxSessions;

    public SipSessionManager() {
        this(Duration.ofMinutes(30), 10000);
    }

    @Inject
    public SipSessionManager(SipServerConfiguration config) {
        this(
                Duration.ofMillis(config != null ? config.getSessionTtlMs() : 1800000),
                config != null ? config.getMaxSessions() : 10000
        );
    }

    public SipSessionManager(Duration sessionTtl, int maxSessions) {
        this.sessionTtl = sessionTtl != null ? sessionTtl : Duration.ofMinutes(30);
        this.maxSessions = maxSessions > 0 ? maxSessions : 10000;
    }

    public SipSession getOrCreateSession(String callId, InetSocketAddress remoteAddress) {
        if (callId == null) {
            return new SipSession("anonymous-" + System.nanoTime(), remoteAddress);
        }

        SipSession existing = sessions.get(callId);
        if (existing != null) {
            if (existing.isExpired(sessionTtl)) {
                LOG.debug("Session for Call-ID {} expired, creating fresh session.", callId);
                sessions.remove(callId);
            } else {
                existing.touch();
                return existing;
            }
        }

        if (sessions.size() >= maxSessions) {
            int evicted = evictExpiredSessions();
            if (sessions.size() >= maxSessions) {
                evictOldestSession();
            }
        }

        return sessions.compute(callId, (id, current) -> {
            if (current != null && !current.isExpired(sessionTtl)) {
                current.touch();
                return current;
            }
            return new SipSession(id, remoteAddress);
        });
    }

    public Optional<SipSession> findSession(String callId) {
        if (callId == null) return Optional.empty();
        SipSession session = sessions.get(callId);
        if (session == null) return Optional.empty();

        if (session.isExpired(sessionTtl)) {
            sessions.remove(callId);
            return Optional.empty();
        }

        session.touch();
        return Optional.of(session);
    }

    public SipSession getSession(String callId) {
        return findSession(callId).orElse(null);
    }

    public void removeSession(String callId) {
        if (callId != null) {
            sessions.remove(callId);
        }
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    /**
     * Evicts any sessions that have expired or reached TERMINATED state.
     * @return the number of evicted sessions
     */
    public int evictExpiredSessions() {
        int evicted = 0;
        Iterator<Map.Entry<String, SipSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SipSession> entry = it.next();
            SipSession session = entry.getValue();
            if (session.getState() == SipSession.State.TERMINATED || session.isExpired(sessionTtl)) {
                it.remove();
                evicted++;
            }
        }
        if (evicted > 0) {
            LOG.debug("Evicted {} expired or terminated SIP session(s). Active sessions: {}", evicted, sessions.size());
        }
        return evicted;
    }

    private void evictOldestSession() {
        String oldestCallId = null;
        Instant oldestTime = Instant.MAX;

        for (Map.Entry<String, SipSession> entry : sessions.entrySet()) {
            Instant accessed = entry.getValue().getLastAccessedAt();
            if (accessed.isBefore(oldestTime)) {
                oldestTime = accessed;
                oldestCallId = entry.getKey();
            }
        }

        if (oldestCallId != null) {
            sessions.remove(oldestCallId);
            LOG.warn("Reached maximum SIP session capacity ({}); evicted oldest session for Call-ID {}", maxSessions, oldestCallId);
        }
    }
}
