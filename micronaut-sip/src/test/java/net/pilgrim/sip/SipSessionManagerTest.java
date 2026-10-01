package net.pilgrim.sip;

import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SipSessionManagerTest {

    @Test
    void testSessionCreationAndTouch() {
        SipSessionManager manager = new SipSessionManager(Duration.ofMinutes(5), 100);
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 5060);

        SipSession session = manager.getOrCreateSession("call-1", addr);
        assertNotNull(session);
        assertEquals("call-1", session.getCallId());
        assertEquals(1, manager.getActiveSessionCount());

        Optional<SipSession> found = manager.findSession("call-1");
        assertTrue(found.isPresent());
        assertEquals(session, found.get());
    }

    @Test
    void testSessionExpirationWithTtl() throws InterruptedException {
        // 50ms TTL for testing
        SipSessionManager manager = new SipSessionManager(Duration.ofMillis(50), 100);
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 5060);

        manager.getOrCreateSession("call-exp", addr);
        assertEquals(1, manager.getActiveSessionCount());

        Thread.sleep(80);

        // findSession should detect expiration, remove it, and return empty
        Optional<SipSession> found = manager.findSession("call-exp");
        assertFalse(found.isPresent());
        assertEquals(0, manager.getActiveSessionCount());
    }

    @Test
    void testMaxSessionCapacityAndEviction() {
        // Max capacity of 3
        SipSessionManager manager = new SipSessionManager(Duration.ofMinutes(10), 3);
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 5060);

        manager.getOrCreateSession("call-1", addr);
        manager.getOrCreateSession("call-2", addr);
        manager.getOrCreateSession("call-3", addr);
        assertEquals(3, manager.getActiveSessionCount());

        // Creating 4th session should trigger eviction of oldest to stay within capacity
        manager.getOrCreateSession("call-4", addr);
        assertTrue(manager.getActiveSessionCount() <= 3);
        assertTrue(manager.findSession("call-4").isPresent());
    }

    @Test
    void testEvictTerminatedSessions() {
        SipSessionManager manager = new SipSessionManager(Duration.ofMinutes(10), 10);
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 5060);

        SipSession s1 = manager.getOrCreateSession("call-term", addr);
        s1.setState(SipSession.State.TERMINATED);

        manager.getOrCreateSession("call-active", addr);
        assertEquals(2, manager.getActiveSessionCount());

        int evicted = manager.evictExpiredSessions();
        assertEquals(1, evicted);
        assertEquals(1, manager.getActiveSessionCount());
        assertFalse(manager.findSession("call-term").isPresent());
        assertTrue(manager.findSession("call-active").isPresent());
    }
}
