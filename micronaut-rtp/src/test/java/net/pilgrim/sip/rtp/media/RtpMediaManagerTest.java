package net.pilgrim.sip.rtp.media;

import net.pilgrim.sip.rtp.RtpPacket;
import net.pilgrim.sip.rtp.config.RtpConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpMediaManagerTest {

    private RtpMediaManager mediaManager;

    @BeforeEach
    void setUp() {
        RtpConfiguration config = new RtpConfiguration();
        config.setPortRangeStart(25000);
        config.setPortRangeEnd(26000);
        config.setBindAddress("127.0.0.1");
        config.setWorkerThreads(4);
        mediaManager = new RtpMediaManager(config);
    }

    @AfterEach
    void tearDown() {
        if (mediaManager != null) {
            mediaManager.close();
        }
    }

    @Test
    void createsAndBindsRtpMediaSession() throws Exception {
        String callId = "call-1001";
        RtpMediaSession session = mediaManager.createSession(callId);

        assertNotNull(session);
        assertEquals(callId, session.getCallId());
        assertTrue(session.getLocalPort() >= 25000 && session.getLocalPort() <= 26000);
        assertTrue(session.getLocalPort() % 2 == 0);
        assertEquals(1, mediaManager.getActiveSessionCount());

        mediaManager.terminateSession(callId);
        assertEquals(0, mediaManager.getActiveSessionCount());
    }

    @Test
    void exchangesAudioBetweenTwoSessions() throws Exception {
        RtpMediaSession aliceSession = mediaManager.createSession("call-alice");
        RtpMediaSession bobSession = mediaManager.createSession("call-bob");

        aliceSession.setRemoteAddress(new InetSocketAddress("127.0.0.1", bobSession.getLocalPort()));
        bobSession.setRemoteAddress(new InetSocketAddress("127.0.0.1", aliceSession.getLocalPort()));

        byte[] pcm16Le = new byte[320]; // 20ms silence frame
        for (int i = 0; i < pcm16Le.length; i++) {
            pcm16Le[i] = (byte) (i % 64);
        }

        StepVerifier.create(bobSession.incomingRtpPackets().take(1))
                .then(() -> aliceSession.sendAudioFrame(pcm16Le, true).block(Duration.ofSeconds(2)))
                .assertNext((RtpPacket packet) -> {
                    assertEquals(0, packet.getPayloadType());
                    assertEquals(160, packet.getPayload().length);
                    assertTrue(packet.isMarker());
                })
                .verifyComplete();

        assertEquals(1, aliceSession.getPacketsSent());
        assertEquals(1, bobSession.getPacketsReceived());
    }

    @Test
    void supportsOneHundredConcurrentSessions() throws Exception {
        int count = 100;
        List<RtpMediaSession> sessions = new ArrayList<>(count);
        Set<Integer> boundPorts = new HashSet<>();

        for (int i = 0; i < count; i++) {
            String callId = "call-" + i;
            RtpMediaSession session = mediaManager.createSession(callId);
            sessions.add(session);
            assertTrue(boundPorts.add(session.getLocalPort()), "Duplicate port: " + session.getLocalPort());
        }

        assertEquals(count, mediaManager.getActiveSessionCount());

        // Terminate half
        for (int i = 0; i < 50; i++) {
            mediaManager.terminateSession("call-" + i);
        }
        assertEquals(50, mediaManager.getActiveSessionCount());

        // Terminate remaining
        for (int i = 50; i < count; i++) {
            mediaManager.terminateSession("call-" + i);
        }
        assertEquals(0, mediaManager.getActiveSessionCount());
    }
}
