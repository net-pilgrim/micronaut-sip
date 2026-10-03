package net.pilgrim.sip;

import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import net.pilgrim.sip.transport.SipNettyServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.io.OutputStream;
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

        String sdpOffer =
                "v=0\r\n" +
                "o=Alice 2000 2000 IN IP4 127.0.0.1\r\n" +
                "s=Offer\r\n" +
                "c=IN IP4 127.0.0.1\r\n" +
                "t=0 0\r\n" +
                "m=audio 31000 RTP/AVP 0\r\n";

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

        String ackSdpAnswer =
                "v=0\r\n" +
                "o=Alice 2001 2001 IN IP4 127.0.0.1\r\n" +
                "s=Answer\r\n" +
                "c=IN IP4 127.0.0.1\r\n" +
                "t=0 0\r\n" +
                "m=audio 31002 RTP/AVP 0\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=recvonly\r\n";
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
            String optionsMsg =
                    "OPTIONS sip:127.0.0.1:" + server.getTcpPort() + " SIP/2.0\r\n" +
                    "Via: SIP/2.0/TCP 127.0.0.1:50999;branch=z9hG4bKkeepalive1\r\n" +
                    "Max-Forwards: 70\r\n" +
                    "From: <sip:ping@127.0.0.1>;tag=ping1\r\n" +
                    "To: <sip:127.0.0.1>\r\n" +
                    "Call-ID: ping-call-123\r\n" +
                    "CSeq: 1 OPTIONS\r\n" +
                    "Content-Length: 0\r\n" +
                    "\r\n";

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
