package net.pilgrim.sip;

import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.dtmf.DtmfSignal;
import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import net.pilgrim.sip.transport.SipNettyServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class SipTcpIntegrationTest {

    @Inject
    SipNettyServer server;

    @Inject
    ReactiveSipClient client;

    @Inject
    SipSessionManager sessionManager;

    @Test
    void testTcpServerIsRunning() {
        assertTrue(server.isRunning());
        assertTrue(server.getTcpPort() > 0);
        assertNotNull(server.getTcpBoundAddress());
    }

    @Test
    void testFullCallFlowOverTcp() {
        InetSocketAddress tcpAddress = new InetSocketAddress("127.0.0.1", server.getTcpPort());
        String callId = "test-tcp-call-" + UUID.randomUUID();

        String sdpOffer = """
                v=0
                o=Alice 2000 2000 IN IP4 127.0.0.1
                s=Offer
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 31000 RTP/AVP 0
                """.replace("\n", "\r\n");

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getTcpPort() + ";transport=tcp")
                .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<sip:bob@127.0.0.1>")
                .callId(callId)
                .contentType("application/sdp")
                .body(sdpOffer)
                .build();

        // 1. Send INVITE over TCP: verify 180 Ringing followed by 200 OK
        Flux<SipResponse> responseStream = client.sendWithProvisional(invite, tcpAddress);

        List<SipResponse> responses = responseStream.collectList().block(Duration.ofSeconds(5));
        assertNotNull(responses);
        assertEquals(2, responses.size(), "Expected 180 Ringing and 200 OK over TCP");

        SipResponse ringing = responses.get(0);
        assertEquals(180, ringing.getStatusCode());
        assertEquals(callId, ringing.getCallId());

        SipResponse ok = responses.get(1);
        assertEquals(200, ok.getStatusCode());
        assertEquals(callId, ok.getCallId());
        assertTrue(ok.getTo().contains("tag="));
        assertEquals("application/sdp", ok.getContentType());
        assertTrue(ok.getBodyAsString().contains("MicronautSIP"));

        // 2. Send ACK over TCP
        client.sendAck(invite, ok, tcpAddress).block(Duration.ofSeconds(2));

        // Verify session state is CONFIRMED
        Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
        assertTrue(sessionOpt.isPresent());

        long deadline = System.currentTimeMillis() + 2000;
        while (sessionOpt.get().getState() != SipSession.State.CONFIRMED && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {}
        }
        assertEquals(SipSession.State.CONFIRMED, sessionOpt.get().getState());

        // 3. Send BYE over TCP
        SipResponse byeResponse = client.sendBye(invite, ok, tcpAddress).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());
        assertEquals(SipSession.State.TERMINATED, sessionOpt.get().getState());
    }

    @Test
    void test100RelPrackFlowTcp() throws Exception {
        InetSocketAddress tcpAddress = new InetSocketAddress("127.0.0.1", server.getTcpPort());
        String callId = "test-100rel-tcp-" + UUID.randomUUID();

        String sdpOffer = """
                v=0
                o=Alice 1000 1000 IN IP4 127.0.0.1
                s=Offer
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 30000 RTP/AVP 0
                """.replace("\n", "\r\n");

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getTcpPort() + ";transport=tcp")
                .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<sip:bob@127.0.0.1>")
                .callId(callId)
                .header(SipHeaders.REQUIRE, "100rel")
                .header("X-Pickup-Delay", "50")
                .contentType("application/sdp")
                .body(sdpOffer)
                .build();
        invite.setTransport(SipTransport.TCP);

        List<SipResponse> responses = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch ringingLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch okLatch = new java.util.concurrent.CountDownLatch(1);

        client.sendWithProvisional(invite, tcpAddress).subscribe(resp -> {
            responses.add(resp);
            if (resp.getStatusCode() == 180) {
                ringingLatch.countDown();
            } else if (resp.getStatusCode() == 200) {
                okLatch.countDown();
            }
        });

        assertTrue(ringingLatch.await(3, java.util.concurrent.TimeUnit.SECONDS), "Expected 180 Ringing");
        SipResponse ringing = responses.get(0);
        assertEquals(180, ringing.getStatusCode());
        assertTrue(ringing.getHeaders().containsToken(SipHeaders.REQUIRE, "100rel"), "180 must contain Require: 100rel");
        assertEquals("1", ringing.getHeaders().getRSeq(), "180 must contain RSeq: 1");
        assertNotNull(ringing.getHeaders().getContact(), "180 must contain Contact");
        assertTrue(ringing.getTo().contains("tag="), "180 must establish early dialog tag");

        // Verify that 200 OK has NOT yet arrived before PRACK is sent
        assertEquals(1, responses.size(), "200 OK must not be sent before PRACK");

        // Send PRACK over TCP
        SipResponse prackResponse = client.sendPrack(invite, ringing, tcpAddress, SipTransport.TCP).block(Duration.ofSeconds(2));
        assertNotNull(prackResponse, "Expected 200 OK response to PRACK");
        assertEquals(200, prackResponse.getStatusCode());

        // Now 200 OK to INVITE must arrive
        assertTrue(okLatch.await(3, java.util.concurrent.TimeUnit.SECONDS), "Expected 200 OK to INVITE after PRACK");
        assertEquals(2, responses.size());
        SipResponse ok = responses.get(1);
        assertEquals(200, ok.getStatusCode());
        assertEquals(ringing.getTo(), ok.getTo(), "200 OK To tag must match early dialog To tag from 180 Ringing");

        // Send ACK and BYE over TCP
        client.sendAck(invite, ok, tcpAddress).block(Duration.ofSeconds(2));
        Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
        assertTrue(sessionOpt.isPresent());

        SipResponse byeResponse = client.sendBye(invite, ok, tcpAddress, SipTransport.TCP).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());
        assertEquals(SipSession.State.TERMINATED, sessionOpt.get().getState());
    }

    @Test
    void testAckTriggersRtpProbeWhenSignalingIsTcp() throws Exception {
        InetSocketAddress tcpAddress = new InetSocketAddress("127.0.0.1", server.getTcpPort());
        String callId = "rtp-probe-tcp-" + UUID.randomUUID();

        try (DatagramSocket rtpReceiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            rtpReceiver.setSoTimeout(3000);
            String sdpOffer = """
                    v=0
                    o=Alice 2010 2010 IN IP4 127.0.0.1
                    s=Offer
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0
                    """.formatted(rtpReceiver.getLocalPort()).replace("\n", "\r\n");

            SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getTcpPort() + ";transport=tcp")
                    .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                    .to("<sip:bob@127.0.0.1>")
                    .callId(callId)
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            List<SipResponse> responses = client.sendWithProvisional(invite, tcpAddress)
                    .collectList()
                    .block(Duration.ofSeconds(5));
            assertNotNull(responses);
            SipResponse ok = responses.get(1);
            client.sendAck(invite, ok, tcpAddress).block(Duration.ofSeconds(2));

            byte[] buf = new byte[1500];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            rtpReceiver.receive(packet);

            byte[] raw = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(), raw, 0, packet.getLength());
            net.pilgrim.sip.rtp.RtpPacket rtpPacket = net.pilgrim.sip.rtp.RtpPacket.parse(raw);
            assertEquals(0, rtpPacket.getPayloadType());
            assertEquals(160, rtpPacket.getPayload().length);
        }
    }

    @Test
    void testLateOfferAnswerFlowOverTcp() {
        InetSocketAddress tcpAddress = new InetSocketAddress("127.0.0.1", server.getTcpPort());
        String callId = "test-tcp-late-offer-" + UUID.randomUUID();

        SipRequest inviteWithoutOffer = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getTcpPort() + ";transport=tcp")
                .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<sip:bob@127.0.0.1>")
                .callId(callId)
                .build();

        List<SipResponse> responses = client.sendWithProvisional(inviteWithoutOffer, tcpAddress)
                .collectList()
                .block(Duration.ofSeconds(5));
        assertNotNull(responses);
        assertEquals(2, responses.size());

        SipResponse ok = responses.get(1);
        assertEquals(200, ok.getStatusCode());
        assertEquals("application/sdp", ok.getContentType());
        assertTrue(ok.getBodyAsString().contains("m=audio"), "Server should include SDP offer in 200 OK");

        String ackSdpAnswer = """
                v=0
                o=Alice 2001 2001 IN IP4 127.0.0.1
                s=Answer
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 31002 RTP/AVP 0
                a=rtpmap:0 PCMU/8000
                a=recvonly
                """.replace("\n", "\r\n");
        client.sendAck(inviteWithoutOffer, ok, tcpAddress, ackSdpAnswer, "application/sdp")
                .block(Duration.ofSeconds(2));

        Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
        assertTrue(sessionOpt.isPresent());
        SipSession session = sessionOpt.get();
        long deadline = System.currentTimeMillis() + 2000;
        while (session.getState() != SipSession.State.CONFIRMED && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {}
        }
        assertEquals(SipSession.State.CONFIRMED, session.getState());
        assertEquals(ackSdpAnswer, session.getAttribute("sdpAnswer"));
        assertEquals(Boolean.FALSE, session.getAttribute("awaitingAckSdpAnswer"));
    }

    @Test
    void testMessageOverTcp() {
        InetSocketAddress tcpAddress = new InetSocketAddress("127.0.0.1", server.getTcpPort());
        SipRequest message = SipRequest.builder(SipMethod.MESSAGE, "sip:bob@127.0.0.1:" + server.getTcpPort() + ";transport=tcp")
                .from("<sip:alice@127.0.0.1>;tag=msgtcp")
                .to("<sip:bob@127.0.0.1>")
                .contentType("text/plain")
                .body("Hello from Micronaut SIP over TCP!")
                .build();

        SipResponse response = client.send(message, tcpAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
    }

    @Test
    void testDtmfOverTcp() {
        InetSocketAddress tcpAddress = new InetSocketAddress("127.0.0.1", server.getTcpPort());
        String callId = "test-tcp-dtmf-" + UUID.randomUUID();

        String sdpOffer = """
                v=0
                o=Alice 2000 2000 IN IP4 127.0.0.1
                s=Offer
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 31000 RTP/AVP 0
                """.replace("\n", "\r\n");

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getTcpPort() + ";transport=tcp")
                .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<sip:bob@127.0.0.1>")
                .callId(callId)
                .contentType("application/sdp")
                .body(sdpOffer)
                .build();

        // 1. Establish call over TCP
        List<SipResponse> responses = client.sendWithProvisional(invite, tcpAddress).collectList().block(Duration.ofSeconds(5));
        assertNotNull(responses);
        SipResponse ok = responses.get(1);
        assertEquals(200, ok.getStatusCode());

        client.sendAck(invite, ok, tcpAddress).block(Duration.ofSeconds(2));

        // 2. Send DTMF via INFO over TCP
        DtmfSignal dtmf = DtmfSignal.of('5', 180);
        SipResponse dtmfResp = client.sendDtmf(invite, ok, dtmf, tcpAddress).block(Duration.ofSeconds(3));
        assertNotNull(dtmfResp);
        assertEquals(200, dtmfResp.getStatusCode());
        assertEquals("5", dtmfResp.getHeaders().get("X-Received-DTMF"));

        Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
        assertTrue(sessionOpt.isPresent());
        assertEquals("5", sessionOpt.get().getAttribute("dtmfDigits"));

        // 3. Bye over TCP
        SipResponse bye = client.sendBye(invite, ok, tcpAddress).block(Duration.ofSeconds(3));
        assertNotNull(bye);
        assertEquals(200, bye.getStatusCode());
    }

    @Test
    void testRegisterOverTcp() {
        InetSocketAddress tcpAddress = new InetSocketAddress("127.0.0.1", server.getTcpPort());
        SipRequest register = SipRequest.builder(SipMethod.REGISTER, "sip:127.0.0.1:" + server.getTcpPort() + ";transport=tcp")
                .from("<sip:alice@127.0.0.1>;tag=regtcp")
                .to("<sip:alice@127.0.0.1>")
                .contact("<sip:alice@127.0.0.1:5070;transport=tcp>")
                .build();

        SipResponse response = client.send(register, tcpAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertEquals("<sip:alice@127.0.0.1:5070;transport=tcp>", response.getContact());
    }

    @Test
    void testOptionsOverTcp() {
        InetSocketAddress tcpAddress = new InetSocketAddress("127.0.0.1", server.getTcpPort());
        SipRequest options = SipRequest.builder(SipMethod.OPTIONS, "sip:127.0.0.1:" + server.getTcpPort() + ";transport=tcp")
                .from("<sip:client@127.0.0.1>;tag=opttcp")
                .to("<sip:127.0.0.1>")
                .build();

        SipResponse response = client.send(options, tcpAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getHeaders().get("Allow").contains("INVITE"));
    }

    @Test
    void testTcpKeepAliveAndFraming() throws Exception {
        // Test raw TCP socket sending keep-alive CRLFs followed by an OPTIONS request
        try (Socket socket = new Socket("127.0.0.1", server.getTcpPort())) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Send keepalive CRLFs (RFC 5626)
            out.write("\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Send OPTIONS
            String optionsMsg = """
                    OPTIONS sip:127.0.0.1:%d SIP/2.0
                    Via: SIP/2.0/TCP 127.0.0.1:50999;branch=z9hG4bKkeepalive1
                    Max-Forwards: 70
                    From: <sip:ping@127.0.0.1>;tag=ping1
                    To: <sip:127.0.0.1>
                    Call-ID: ping-call-123
                    CSeq: 1 OPTIONS
                    Content-Length: 0

                    """.formatted(server.getTcpPort()).replace("\n", "\r\n");

            out.write(optionsMsg.getBytes(StandardCharsets.UTF_8));
            out.flush();

            byte[] buffer = new byte[1024];
            int read = in.read(buffer);
            assertTrue(read > 0);

            String responseText = new String(buffer, 0, read, StandardCharsets.UTF_8);
            assertTrue(responseText.startsWith("SIP/2.0 200 OK"));
            assertTrue(responseText.contains("ping-call-123"));
        }
    }
}
