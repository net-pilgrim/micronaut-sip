package net.pilgrim.controller;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import net.pilgrim.sip.transport.SipNettyServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = "test")
public class MrcpVoiceAssistantIntegrationTest {

    @Inject
    SipNettyServer server;

    @Inject
    SipSessionManager sessionManager;

    @Inject
    ReactiveSipClient client;

    @Test
    void testAiAssistantInviteAndSdpAnswer() {
        int serverPort = server.getUdpPort() > 0 ? server.getUdpPort() : server.getPort();
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", serverPort);

        String callId = "test-ai-" + UUID.randomUUID();
        String sdpOffer = "v=0\r\n"
                + "o=caller 12345 12345 IN IP4 127.0.0.1\r\n"
                + "s=Call\r\n"
                + "c=IN IP4 127.0.0.1\r\n"
                + "t=0 0\r\n"
                + "m=audio 50000 RTP/AVP 0 8 101\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n"
                + "a=sendrecv\r\n";

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:ai@127.0.0.1:" + serverPort)
                .from("<sip:alice@127.0.0.1>;tag=callerTag1")
                .to("<sip:ai@127.0.0.1>")
                .callId(callId)
                .body(sdpOffer, "application/sdp")
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode(), "INVITE to sip:ai must return 200 OK");
        assertNotNull(response.getBodyAsString());
        assertTrue(response.getBodyAsString().contains("m=audio"), "SDP answer must contain audio media description");

        // Send ACK
        client.sendAck(invite, response, serverAddress).block(Duration.ofSeconds(2));

        // Verify session state
        SipSession session = sessionManager.getSession(callId);
        assertNotNull(session);
        long confirmDeadline = System.currentTimeMillis() + 2000;
        while (session.getState() != SipSession.State.CONFIRMED && System.currentTimeMillis() < confirmDeadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {}
        }
        assertEquals(SipSession.State.CONFIRMED, session.getState());

        // Send BYE to cleanly terminate call
        SipRequest bye = SipRequest.builder(SipMethod.BYE, "sip:ai@127.0.0.1:" + serverPort)
                .from("<sip:alice@127.0.0.1>;tag=callerTag1")
                .to(response.getTo())
                .callId(callId)
                .build();

        SipResponse byeResponse = client.send(bye, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());
        assertEquals(SipSession.State.TERMINATED, session.getState());
    }

    @Test
    void testMrcpAliasInvite() {
        int serverPort = server.getUdpPort() > 0 ? server.getUdpPort() : server.getPort();
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", serverPort);

        String callId = "test-mrcp-alias-" + UUID.randomUUID();
        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:mrcp@127.0.0.1:" + serverPort)
                .from("<sip:bob@127.0.0.1>;tag=bobTag1")
                .to("<sip:mrcp@127.0.0.1>")
                .callId(callId)
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode(), "INVITE to sip:mrcp alias must return 200 OK");
    }
}
