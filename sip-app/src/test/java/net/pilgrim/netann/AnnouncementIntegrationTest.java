package net.pilgrim.netann;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import net.pilgrim.netann.controller.AnnouncementController;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import net.pilgrim.sip.transport.SipNettyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = "test")
public class AnnouncementIntegrationTest {

    @Inject
    SipNettyServer server;

    @Inject
    SipSessionManager sessionManager;

    @Inject
    AnnouncementController controller;

    @Inject
    ReactiveSipClient client;

    @Test
    void testMissingPlayParameterReturns400() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:annc@127.0.0.1:" + server.getPort())
                .from("<sip:caller@127.0.0.1>;tag=call101")
                .to("<sip:annc@127.0.0.1>")
                .callId("test-annc-missing-play-" + UUID.randomUUID())
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(400, response.getStatusCode(), "Missing play parameter must yield 400 Bad Request");
        assertTrue(response.getReasonPhrase().contains("Mandatory play parameter missing"),
                "Reason phrase should state mandatory play parameter missing");
    }

    @Test
    void testNonExistentAnnouncementReturns404() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:annc@127.0.0.1:" + server.getPort()
                        + ";play=file:///non/existent/announcement_test_file.wav")
                .from("<sip:caller@127.0.0.1>;tag=call102")
                .to("<sip:annc@127.0.0.1>")
                .callId("test-annc-404-" + UUID.randomUUID())
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(404, response.getStatusCode(), "Unknown content must yield 404 Not Found");
        assertTrue(response.getReasonPhrase().contains("Announcement content not found"),
                "Reason phrase should state announcement content not found");
    }

    @Test
    void testUnsupportedServiceIndicatorReturns488() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        // e.g. sip:conf=123@... or sip:bob@...
        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:conf=meetingRoom1@127.0.0.1:" + server.getPort())
                .from("<sip:caller@127.0.0.1>;tag=call103")
                .to("<sip:conf=meetingRoom1@127.0.0.1>")
                .callId("test-annc-488-" + UUID.randomUUID())
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(488, response.getStatusCode(), "Unsupported service must yield 488 Not Acceptable Here per RFC 4240 §2");
    }

    @Test
    void testSuccessfulAnnouncementPlaybackAndRtpStreaming() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "annc-play-" + UUID.randomUUID();

        try (DatagramSocket rtpReceiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            rtpReceiver.setSoTimeout(3000);
            int clientRtpPort = rtpReceiver.getLocalPort();

            String sdpOffer = """
                    v=0
                    o=Caller 1000 1000 IN IP4 127.0.0.1
                    s=NetAnnTest
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0
                    """.formatted(clientRtpPort).replace("\n", "\r\n");

            // Play a short 100ms tone with repeat=2, delay=20ms
            String anncUri = "sip:annc@127.0.0.1:" + server.getPort()
                    + ";play=builtin:tone:440,100;repeat=2;delay=20";

            SipRequest invite = SipRequest.builder(SipMethod.INVITE, anncUri)
                    .from("<sip:caller@127.0.0.1>;tag=call-annc-1")
                    .to("<sip:annc@127.0.0.1>")
                    .callId(callId)
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            // 1. Send INVITE and verify 200 OK
            SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(ok);
            assertEquals(200, ok.getStatusCode());
            assertNotNull(ok.getContact());
            assertTrue(ok.getContact().contains("annc"));
            assertEquals("application/sdp", ok.getContentType());
            assertTrue(ok.getBodyAsString().contains("m=audio"));

            // 2. Send ACK to trigger announcement playback
            client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

            // Verify session is CONFIRMED (polling for async UDP datagram reception)
            Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
            assertTrue(sessionOpt.isPresent());
            long confirmDeadline = System.currentTimeMillis() + 2000;
            while (sessionOpt.get().getState() != SipSession.State.CONFIRMED && System.currentTimeMillis() < confirmDeadline) {
                Thread.sleep(10);
            }
            assertEquals(SipSession.State.CONFIRMED, sessionOpt.get().getState());

            // 3. Receive RTP packets on client's RTP port
            AtomicInteger rtpPacketsReceived = new AtomicInteger(0);
            byte[] buf = new byte[1024];
            long deadline = System.currentTimeMillis() + 2500;
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    rtpReceiver.receive(packet);
                    if (packet.getLength() > 12) {
                        rtpPacketsReceived.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Socket timeout
                    break;
                }
            }

            assertTrue(rtpPacketsReceived.get() >= 5,
                    "Expected multiple RTP packets from NetAnn server, but received: " + rtpPacketsReceived.get());
        }
    }

    @Test
    void testBundledWavFilePlayback() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "annc-wav-" + UUID.randomUUID();

        try (DatagramSocket rtpReceiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            rtpReceiver.setSoTimeout(3000);
            int clientRtpPort = rtpReceiver.getLocalPort();

            String sdpOffer = """
                    v=0
                    o=Caller 2000 2000 IN IP4 127.0.0.1
                    s=WavTest
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0
                    """.formatted(clientRtpPort).replace("\n", "\r\n");

            // Play the bundled welcome.wav prompt
            String anncUri = "sip:annc@127.0.0.1:" + server.getPort()
                    + ";play=prompts/welcome.wav;duration=200";

            SipRequest invite = SipRequest.builder(SipMethod.INVITE, anncUri)
                    .from("<sip:caller@127.0.0.1>;tag=call-annc-wav")
                    .to("<sip:annc@127.0.0.1>")
                    .callId(callId)
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(ok);
            assertEquals(200, ok.getStatusCode());

            client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

            byte[] buf = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            rtpReceiver.receive(packet);
            assertTrue(packet.getLength() > 12, "RTP packet should contain header and payload");
        }
    }

    @Test
    void testEarlyByeTerminatesPlayback() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "annc-early-bye-" + UUID.randomUUID();

        String sdpOffer = """
                v=0
                o=Caller 3000 3000 IN IP4 127.0.0.1
                s=EarlyByeTest
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 39000 RTP/AVP 0
                """.replace("\n", "\r\n");

        String anncUri = "sip:annc@127.0.0.1:" + server.getPort()
                + ";play=builtin:tone:440,5000;repeat=forever";

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, anncUri)
                .from("<sip:caller@127.0.0.1>;tag=call-early-bye")
                .to("<sip:annc@127.0.0.1>")
                .callId(callId)
                .contentType("application/sdp")
                .body(sdpOffer)
                .build();

        SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(ok);
        assertEquals(200, ok.getStatusCode());

        client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));
        long playerDeadline = System.currentTimeMillis() + 2000;
        while (!controller.getActivePlayers().containsKey(callId) && System.currentTimeMillis() < playerDeadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {}
        }
        assertTrue(controller.getActivePlayers().containsKey(callId));

        // Client hangs up early with BYE
        SipResponse byeResponse = client.sendBye(invite, ok, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());

        // Player should be cleaned up and session terminated
        assertFalse(controller.getActivePlayers().containsKey(callId));
        Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
        assertTrue(sessionOpt.isPresent());
        assertEquals(SipSession.State.TERMINATED, sessionOpt.get().getState());
    }

    @Test
    void testAnnouncementCompletionEmitsBye() throws Exception {
        int serverPort = server.getPort();
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", serverPort);
        String callId = "annc-autobye-" + UUID.randomUUID();

        try (DatagramSocket clientSipSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
             DatagramSocket clientRtpSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {

            clientSipSocket.setSoTimeout(4000);
            clientRtpSocket.setSoTimeout(4000);
            int clientSipPort = clientSipSocket.getLocalPort();
            int clientRtpPort = clientRtpSocket.getLocalPort();

            String sdpOffer = """
                    v=0
                    o=Caller 4000 4000 IN IP4 127.0.0.1
                    s=AutoByeTest
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0
                    """.formatted(clientRtpPort).replace("\n", "\r\n");

            // Short 60ms tone with duration=60ms
            String anncUri = "sip:annc@127.0.0.1:" + serverPort
                    + ";play=builtin:tone:440,60;duration=60";

            SipRequest invite = SipRequest.builder(SipMethod.INVITE, anncUri)
                    .from("<sip:caller@127.0.0.1:" + clientSipPort + ">;tag=autobye-caller")
                    .to("<sip:annc@127.0.0.1>")
                    .contact("<sip:caller@127.0.0.1:" + clientSipPort + ">")
                    .callId(callId)
                    .cseq(1, SipMethod.INVITE)
                    .via("SIP/2.0/UDP 127.0.0.1:" + clientSipPort + ";branch=z9hG4bK-inv-1;rport")
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            // 1. Send INVITE from raw client socket
            net.pilgrim.sip.parser.SipEncoder encoder = new net.pilgrim.sip.parser.SipEncoder();
            net.pilgrim.sip.parser.SipParser parser = new net.pilgrim.sip.parser.SipParser();

            byte[] invBytes = encoder.encode(invite);
            clientSipSocket.send(new DatagramPacket(invBytes, invBytes.length, serverAddress));

            // 2. Receive 200 OK
            byte[] rxBuf = new byte[2048];
            DatagramPacket rxPacket = new DatagramPacket(rxBuf, rxBuf.length);
            clientSipSocket.receive(rxPacket);

            SipResponse ok = (SipResponse) parser.parse(java.util.Arrays.copyOf(rxBuf, rxPacket.getLength()));
            assertEquals(200, ok.getStatusCode());

            // 3. Send ACK
            SipRequest ack = new SipRequest(SipMethod.ACK, invite.getUri());
            ack.getHeaders().setCallId(callId);
            ack.getHeaders().setFrom(ok.getFrom());
            ack.getHeaders().setTo(ok.getTo());
            ack.getHeaders().setCSeq("1 ACK");
            ack.getHeaders().addVia("SIP/2.0/UDP 127.0.0.1:" + clientSipPort + ";branch=z9hG4bK-ack-1;rport");
            ack.getHeaders().setMaxForwards(70);
            ack.getHeaders().setContentLength(0);

            byte[] ackBytes = encoder.encode(ack);
            clientSipSocket.send(new DatagramPacket(ackBytes, ackBytes.length, serverAddress));

            // 4. Client receives BYE from server when announcement playback finishes!
            rxPacket = new DatagramPacket(rxBuf, rxBuf.length);
            clientSipSocket.receive(rxPacket);

            SipMessage byeMsg = parser.parse(java.util.Arrays.copyOf(rxBuf, rxPacket.getLength()));
            assertTrue(byeMsg instanceof SipRequest, "Server must send BYE request after announcement completes");
            SipRequest byeReq = (SipRequest) byeMsg;
            assertEquals(SipMethod.BYE, byeReq.getMethod());
            assertEquals(callId, byeReq.getCallId());

            // 5. Send 200 OK back to BYE
            SipResponse byeOk = byeReq.createResponse(200);
            byte[] byeOkBytes = encoder.encode(byeOk);
            clientSipSocket.send(new DatagramPacket(byeOkBytes, byeOkBytes.length, serverAddress));

            // Wait briefly for server to finalize termination
            Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
            assertTrue(sessionOpt.isPresent());
            long deadline = System.currentTimeMillis() + 1000;
            while (sessionOpt.get().getState() != SipSession.State.TERMINATED && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(SipSession.State.TERMINATED, sessionOpt.get().getState());
        }
    }

    @Test
    void testAnnouncementOverTcp() {
        InetSocketAddress tcpAddress = new InetSocketAddress("127.0.0.1", server.getTcpPort());
        String callId = "annc-tcp-" + UUID.randomUUID();

        String sdpOffer = """
                v=0
                o=Caller 5000 5000 IN IP4 127.0.0.1
                s=TcpAnncTest
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 40000 RTP/AVP 0
                """.replace("\n", "\r\n");

        String anncUri = "sip:annc@127.0.0.1:" + server.getTcpPort()
                + ";transport=tcp;play=builtin:tone:440,300";

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, anncUri)
                .from("<sip:caller@127.0.0.1>;tag=tcp-annc-1")
                .to("<sip:annc@127.0.0.1>")
                .callId(callId)
                .contentType("application/sdp")
                .body(sdpOffer)
                .build();
        invite.setTransport(SipTransport.TCP);

        SipResponse ok = client.send(invite, tcpAddress, SipTransport.TCP).block(Duration.ofSeconds(3));
        assertNotNull(ok);
        assertEquals(200, ok.getStatusCode());
        assertTrue(ok.getContact().contains("annc"));

        client.sendAck(invite, ok, tcpAddress, SipTransport.TCP).block(Duration.ofSeconds(2));

        Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
        assertTrue(sessionOpt.isPresent());

        SipResponse byeResponse = client.sendBye(invite, ok, tcpAddress, SipTransport.TCP).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());
        assertEquals(SipSession.State.TERMINATED, sessionOpt.get().getState());
    }

    @Test
    void testOptionsQuery() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest options = SipRequest.builder(SipMethod.OPTIONS, "sip:127.0.0.1:" + server.getPort())
                .from("<sip:scanner@127.0.0.1>;tag=scan1")
                .to("<sip:127.0.0.1>")
                .callId("options-scan-" + UUID.randomUUID())
                .build();

        SipResponse response = client.send(options, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getHeaders().get(SipHeaders.ALLOW).contains("INVITE"));
    }
}
