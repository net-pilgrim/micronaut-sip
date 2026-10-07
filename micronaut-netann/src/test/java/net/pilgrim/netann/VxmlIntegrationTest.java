package net.pilgrim.netann;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import net.pilgrim.netann.controller.AnnouncementController;
import net.pilgrim.netann.controller.VxmlController;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.dtmf.DtmfSignal;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import net.pilgrim.sip.transport.SipNettyServer;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = "test")
public class VxmlIntegrationTest {

    @Inject
    SipNettyServer server;

    @Inject
    SipSessionManager sessionManager;

    @Inject
    VxmlController vxmlController;

    @Inject
    AnnouncementController announcementController;

    @Inject
    ReactiveSipClient client;

    @Test
    void testMissingVoicexmlParameterReturns400() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:dialog@127.0.0.1:" + server.getPort())
                .from("<sip:caller@127.0.0.1>;tag=call-vxml-101")
                .to("<sip:dialog@127.0.0.1>")
                .callId("test-vxml-missing-param-" + UUID.randomUUID())
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(400, response.getStatusCode(), "Missing voicexml parameter must return 400 Bad Request");
        assertTrue(response.getReasonPhrase().contains("Mandatory voicexml parameter missing"));
    }

    @Test
    void testNonExistentVoiceXmlReturns404() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:dialog@127.0.0.1:" + server.getPort()
                        + ";voicexml=file:///non/existent/script.vxml")
                .from("<sip:caller@127.0.0.1>;tag=call-vxml-102")
                .to("<sip:dialog@127.0.0.1>")
                .callId("test-vxml-404-" + UUID.randomUUID())
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(404, response.getStatusCode(), "Non-existent VoiceXML document must yield 404 Not Found");
    }

    @Test
    void testSuccessfulVxmlDialogWithDtmfNavigation() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "vxml-dialog-" + UUID.randomUUID();

        try (DatagramSocket rtpReceiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            rtpReceiver.setSoTimeout(3000);
            int clientRtpPort = rtpReceiver.getLocalPort();

            String sdpOffer = """
                    v=0
                    o=Tester 1000 1000 IN IP4 127.0.0.1
                    s=VoiceXMLTest
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0 8
                    a=rtpmap:0 PCMU/8000
                    a=rtpmap:8 PCMA/8000
                    a=sendrecv
                    """.formatted(clientRtpPort);

            SipRequest invite = SipRequest.builder(SipMethod.INVITE,
                            "sip:dialog@127.0.0.1:" + server.getPort() + ";voicexml=classpath:vxml/sample_menu.vxml")
                    .from("<sip:caller@127.0.0.1>;tag=caller-tag-vxml")
                    .to("<sip:dialog@127.0.0.1>")
                    .callId(callId)
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            // 1. Send INVITE and verify 200 OK
            SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(ok);
            assertEquals(200, ok.getStatusCode());
            assertNotNull(ok.getContact());
            assertTrue(ok.getContact().contains("dialog"), "Contact header must contain dialog");
            assertEquals("application/sdp", ok.getContentType());
            assertTrue(ok.getBodyAsString().contains("m=audio"));

            // 2. Send ACK to start VXML dialog execution
            client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

            // Verify session is confirmed
            Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
            assertTrue(sessionOpt.isPresent());
            long confirmDeadline = System.currentTimeMillis() + 2000;
            while (sessionOpt.get().getState() != SipSession.State.CONFIRMED && System.currentTimeMillis() < confirmDeadline) {
                Thread.sleep(10);
            }
            assertEquals(SipSession.State.CONFIRMED, sessionOpt.get().getState());

            // 3. Receive RTP prompt packets emitted by the VXML interpreter
            byte[] buf = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            rtpReceiver.receive(packet);
            assertTrue(packet.getLength() > 12, "RTP packet should contain header and payload");

            // 4. Send mid-dialog DTMF '1' via SIP INFO (RFC 6086) to select Sales
            SipRequest info = SipRequest.builder(SipMethod.INFO, "sip:dialog@127.0.0.1:" + server.getPort())
                    .from(invite.getFrom())
                    .to(ok.getTo())
                    .callId(callId)
                    .build();
            info.setDtmf(new DtmfSignal('1', 160));

            SipResponse infoResponse = client.send(info, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(infoResponse);
            assertEquals(200, infoResponse.getStatusCode());
            assertEquals("1", infoResponse.getHeaders().get("X-Received-DTMF"));

            // 5. Caller sends early or dialog completion BYE
            SipResponse byeResponse = client.sendBye(invite, ok, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(byeResponse);
            assertEquals(200, byeResponse.getStatusCode());
            assertEquals(SipSession.State.TERMINATED, sessionOpt.get().getState());
        }
    }

    @Test
    void testEarlyByeTerminatesVxmlSession() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "vxml-early-bye-" + UUID.randomUUID();

        String sdpOffer = """
                v=0
                o=Tester 1000 1000 IN IP4 127.0.0.1
                s=VoiceXMLTest
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 39482 RTP/AVP 0 8
                a=rtpmap:0 PCMU/8000
                a=sendrecv
                """;

        SipRequest invite = SipRequest.builder(SipMethod.INVITE,
                        "sip:dialog@127.0.0.1:" + server.getPort() + ";voicexml=classpath:vxml/sample_menu.vxml")
                .from("<sip:caller@127.0.0.1>;tag=caller-early-bye")
                .to("<sip:dialog@127.0.0.1>")
                .callId(callId)
                .contentType("application/sdp")
                .body(sdpOffer)
                .build();

        SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(ok);
        assertEquals(200, ok.getStatusCode());

        client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));
        long sessionDeadline = System.currentTimeMillis() + 2000;
        while (!vxmlController.getActiveSessions().containsKey(callId) && System.currentTimeMillis() < sessionDeadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {}
        }
        assertTrue(vxmlController.getActiveSessions().containsKey(callId));

        // Client hangs up early with BYE
        SipResponse byeResponse = client.sendBye(invite, ok, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());

        // Session and players must be removed
        assertFalse(vxmlController.getActiveSessions().containsKey(callId));
        assertFalse(vxmlController.getActivePlayers().containsKey(callId));
    }

    @Test
    void testVxmlOverVxmlUriScheme() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "vxml-uri-test-" + UUID.randomUUID();

        String sdpOffer = """
                v=0
                o=Tester 1000 1000 IN IP4 127.0.0.1
                s=VoiceXMLTest
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 39484 RTP/AVP 0 8
                a=rtpmap:0 PCMU/8000
                a=sendrecv
                """;

        SipRequest invite = SipRequest.builder(SipMethod.INVITE,
                        "sip:vxml@127.0.0.1:" + server.getPort() + ";voicexml=classpath:vxml/sample_menu.vxml")
                .from("<sip:caller@127.0.0.1>;tag=caller-vxml-uri")
                .to("<sip:vxml@127.0.0.1>")
                .callId(callId)
                .contentType("application/sdp")
                .body(sdpOffer)
                .build();

        SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(ok);
        assertEquals(200, ok.getStatusCode());
        assertNotNull(ok.getContact());
        assertTrue(ok.getContact().contains("vxml"));

        client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));
        long sessionDeadline = System.currentTimeMillis() + 2000;
        while (!vxmlController.getActiveSessions().containsKey(callId) && System.currentTimeMillis() < sessionDeadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {}
        }
        assertTrue(vxmlController.getActiveSessions().containsKey(callId));

        SipResponse byeResponse = client.sendBye(invite, ok, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());
    }

    @Test
    void testExistingAnnouncementServiceRemainsUntouched() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "annc-unaffected-" + UUID.randomUUID();

        String sdpOffer = """
                v=0
                o=Tester 1000 1000 IN IP4 127.0.0.1
                s=AnncTest
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 39486 RTP/AVP 0 8
                a=rtpmap:0 PCMU/8000
                a=sendrecv
                """;

        SipRequest invite = SipRequest.builder(SipMethod.INVITE,
                        "sip:annc@127.0.0.1:" + server.getPort() + ";play=builtin:tone:440,500")
                .from("<sip:caller@127.0.0.1>;tag=caller-annc")
                .to("<sip:annc@127.0.0.1>")
                .callId(callId)
                .contentType("application/sdp")
                .body(sdpOffer)
                .build();

        SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(ok);
        assertEquals(200, ok.getStatusCode());
        assertTrue(ok.getContact().contains("annc"), "Announcement service must advertise sip:annc@");

        client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));
        long playerDeadline = System.currentTimeMillis() + 2000;
        while (!announcementController.getActivePlayers().containsKey(callId) && System.currentTimeMillis() < playerDeadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {}
        }
        assertTrue(announcementController.getActivePlayers().containsKey(callId), "AnnouncementController must handle annc");

        SipResponse bye = client.sendBye(invite, ok, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(bye);
        assertEquals(200, bye.getStatusCode());
        assertFalse(announcementController.getActivePlayers().containsKey(callId));
    }
}
