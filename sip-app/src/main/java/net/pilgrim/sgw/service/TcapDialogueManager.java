package net.pilgrim.sgw.service;

import jakarta.inject.Singleton;
import net.pilgrim.sgw.model.TcapMessage;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages active TCAP dialogue sessions, indexing by SIP Call-ID and server DTID.
 */
@Singleton
public class TcapDialogueManager {

    private static final Logger LOG = LoggerFactory.getLogger(TcapDialogueManager.class);

    private final AtomicLong dialogueCounter = new AtomicLong(1000);
    private final Map<String, TcapDialogueSession> sessionsByCallId = new ConcurrentHashMap<>();
    private final Map<String, TcapDialogueSession> sessionsByDtid = new ConcurrentHashMap<>();

    /**
     * Creates and stores a new dialogue session.
     */
    public TcapDialogueSession createSession(String sipCallId,
                                             String clientOtid,
                                             String applicationContext,
                                             InetSocketAddress remoteAddress,
                                             SipRequest originalInvite,
                                             SipResponse originalOk) {
        String dtid = "sgw-tx-" + dialogueCounter.incrementAndGet();
        TcapDialogueSession session = new TcapDialogueSession(
                dtid,
                sipCallId,
                clientOtid,
                applicationContext,
                remoteAddress,
                originalInvite,
                originalOk
        );

        sessionsByCallId.put(sipCallId, session);
        sessionsByDtid.put(dtid, session);
        LOG.info("Created TCAP dialogue session: dtid={}, clientOtid={}, callId={}", dtid, clientOtid, sipCallId);
        return session;
    }

    public Optional<TcapDialogueSession> findByCallId(String sipCallId) {
        if (sipCallId == null) return Optional.empty();
        return Optional.ofNullable(sessionsByCallId.get(sipCallId));
    }

    public Optional<TcapDialogueSession> findByDtid(String dtid) {
        if (dtid == null) return Optional.empty();
        return Optional.ofNullable(sessionsByDtid.get(dtid));
    }

    /**
     * Finds a session by either Call-ID or DTID.
     */
    public Optional<TcapDialogueSession> findSession(String callId, String dtid) {
        if (dtid != null) {
            TcapDialogueSession s = sessionsByDtid.get(dtid);
            if (s != null) return Optional.of(s);
        }
        if (callId != null) {
            TcapDialogueSession s = sessionsByCallId.get(callId);
            if (s != null) return Optional.of(s);
        }
        return Optional.empty();
    }

    /**
     * Marks session terminated and cleans up.
     */
    public Optional<TcapDialogueSession> terminateSession(String sipCallId) {
        if (sipCallId == null) return Optional.empty();
        TcapDialogueSession session = sessionsByCallId.remove(sipCallId);
        if (session != null) {
            session.setState(TcapDialogueSession.State.TERMINATED);
            sessionsByDtid.remove(session.getDialogueId());
            LOG.info("Terminated TCAP dialogue session: dtid={}, callId={}", session.getDialogueId(), sipCallId);
            return Optional.of(session);
        }
        return Optional.empty();
    }

    /**
     * Marks session aborted and cleans up.
     */
    public Optional<TcapDialogueSession> abortSession(String sipCallId, String reason) {
        if (sipCallId == null) return Optional.empty();
        TcapDialogueSession session = sessionsByCallId.remove(sipCallId);
        if (session != null) {
            session.setState(TcapDialogueSession.State.ABORTED);
            session.recordMessage(TcapMessage.abort(session.getDialogueId(), reason));
            sessionsByDtid.remove(session.getDialogueId());
            LOG.info("Aborted TCAP dialogue session: dtid={}, callId={}, reason={}", session.getDialogueId(), sipCallId, reason);
            return Optional.of(session);
        }
        return Optional.empty();
    }

    public int getActiveCount() {
        return sessionsByCallId.size();
    }

    public Collection<TcapDialogueSession> getAllSessions() {
        return Collections.unmodifiableCollection(sessionsByCallId.values());
    }

    public void cleanupExpired(Duration ttl) {
        Instant threshold = Instant.now().minus(ttl);
        sessionsByCallId.entrySet().removeIf(entry -> {
            TcapDialogueSession sess = entry.getValue();
            if (sess.getLastAccessedAt().isBefore(threshold)) {
                LOG.info("Evicting expired TCAP dialogue session: dtid={}, callId={}", sess.getDialogueId(), sess.getSipCallId());
                sessionsByDtid.remove(sess.getDialogueId());
                return true;
            }
            return false;
        });
    }

    public void clearAll() {
        sessionsByCallId.clear();
        sessionsByDtid.clear();
    }
}
