package net.pilgrim.sip;

import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.dtmf.DtmfSignal;
import net.pilgrim.sip.model.SipHeaders;
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
import reactor.test.StepVerifier;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class SipIntegrationTest {

    @Inject
    SipNettyServer server;

    @Inject
    ReactiveSipClient client;

    @Inject
    SipSessionManager sessionManager;

    @Inject
    net.pilgrim.sip.router.SipDispatcher dispatcher;

    @Inject
    net.pilgrim.sip.rtp.media.RtpMediaManager mediaManager;

    @org.junit.jupiter.api.BeforeEach
    @org.junit.jupiter.api.AfterEach
    void cleanupCaches() {
        dispatcher.clearTimerCaches();
    }

    @Test
    void testCustomFilterAppliedOverNetwork() {
        net.pilgrim.sip.filter.SipServerFilter filter = (req, chain) ->
                reactor.core.publisher.Flux.from(chain.proceed(req)).map(resp -> {
                    resp.getHeaders().set("X-Integration-Filter", "applied-over-network");
                    return resp;
                });
        dispatcher.addFilter(filter);
        try {
            InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
            SipRequest options = SipRequest.builder(SipMethod.OPTIONS, "sip:test@127.0.0.1:" + server.getPort())
                    .from("<sip:alice@127.0.0.1>;tag=filter-test")
                    .to("<sip:bob@127.0.0.1>")
                    .callId("filter-test-" + UUID.randomUUID())
                    .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=z9hG4bK-filter-1;rport")
                    .build();

            SipResponse response = client.send(options, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(response);
            assertEquals(200, response.getStatusCode());
            assertEquals("applied-over-network", response.getHeaders().get("X-Integration-Filter"));
        } finally {
            dispatcher.removeFilter(filter);
        }
    }

    @Test
    void testServerIsRunning() {
        assertTrue(server.isRunning());
        assertTrue(server.getPort() > 0);
    }

    @Test
    void testFullCallFlow() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "test-call-" + UUID.randomUUID();

        String sdpOffer = """
                v=0
                o=Alice 1000 1000 IN IP4 127.0.0.1
                s=Offer
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 30000 RTP/AVP 0
                """.replace("\n", "\r\n");

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<sip:bob@127.0.0.1>")
                .callId(callId)
                .contentType("application/sdp")
                .body(sdpOffer)
                .build();

        // 1. Send INVITE and verify stream of 180 Ringing followed by 200 OK
        Flux<SipResponse> responseStream = client.sendWithProvisional(invite, serverAddress);

        List<SipResponse> responses = responseStream.collectList().block(Duration.ofSeconds(5));
        assertNotNull(responses);
        assertEquals(2, responses.size(), "Expected 180 Ringing and 200 OK");

        SipResponse ringing = responses.get(0);
        assertEquals(180, ringing.getStatusCode());
        assertEquals("Ringing", ringing.getReasonPhrase());
        assertEquals(callId, ringing.getCallId());

        SipResponse ok = responses.get(1);
        assertEquals(200, ok.getStatusCode());
        assertEquals("OK", ok.getReasonPhrase());
        assertEquals(callId, ok.getCallId());
        assertTrue(ok.getTo().contains("tag="));
        assertEquals("application/sdp", ok.getContentType());
        assertTrue(ok.getBodyAsString().contains("MicronautSIP"));

        // 2. Send ACK
        client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

        // Verify session state is CONFIRMED (allowing asynchronous UDP reception)
        Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
        assertTrue(sessionOpt.isPresent());

        long deadline = System.currentTimeMillis() + 2000;
        while (sessionOpt.get().getState() != SipSession.State.CONFIRMED && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {}
        }
        assertEquals(SipSession.State.CONFIRMED, sessionOpt.get().getState());

        // 3. Send BYE
        SipResponse byeResponse = client.sendBye(invite, ok, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());
        assertEquals(SipSession.State.TERMINATED, sessionOpt.get().getState());
    }

    @Test
    void test100RelPrackFlowUdp() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "test-100rel-udp-" + UUID.randomUUID();

        String sdpOffer = """
                v=0
                o=Alice 1000 1000 IN IP4 127.0.0.1
                s=Offer
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 30000 RTP/AVP 0
                """.replace("\n", "\r\n");

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<sip:bob@127.0.0.1>")
                .callId(callId)
                .header(SipHeaders.REQUIRE, "100rel")
                .header("X-Pickup-Delay", "50")
                .contentType("application/sdp")
                .body(sdpOffer)
                .build();

        List<SipResponse> responses = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch ringingLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch okLatch = new java.util.concurrent.CountDownLatch(1);

        client.sendWithProvisional(invite, serverAddress).subscribe(resp -> {
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

        // Send PRACK
        SipResponse prackResponse = client.sendPrack(invite, ringing, serverAddress).block(Duration.ofSeconds(2));
        assertNotNull(prackResponse, "Expected 200 OK response to PRACK");
        assertEquals(200, prackResponse.getStatusCode());

        // Now 200 OK to INVITE must arrive
        assertTrue(okLatch.await(3, java.util.concurrent.TimeUnit.SECONDS), "Expected 200 OK to INVITE after PRACK");
        assertEquals(2, responses.size());
        SipResponse ok = responses.get(1);
        assertEquals(200, ok.getStatusCode());
        assertEquals(ringing.getTo(), ok.getTo(), "200 OK To tag must match early dialog To tag from 180 Ringing");

        // Send ACK and BYE
        client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));
        Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
        assertTrue(sessionOpt.isPresent());

        SipResponse byeResponse = client.sendBye(invite, ok, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());
        assertEquals(SipSession.State.TERMINATED, sessionOpt.get().getState());
    }

    @Test
    void testAckTriggersRtpProbeOverUdp() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "rtp-probe-udp-" + UUID.randomUUID();

        try (DatagramSocket rtpReceiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            rtpReceiver.setSoTimeout(3000);
            String sdpOffer = """
                    v=0
                    o=Alice 1010 1010 IN IP4 127.0.0.1
                    s=Offer
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0
                    """.formatted(rtpReceiver.getLocalPort()).replace("\n", "\r\n");

            SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getPort())
                    .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                    .to("<sip:bob@127.0.0.1>")
                    .callId(callId)
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            List<SipResponse> responses = client.sendWithProvisional(invite, serverAddress)
                    .collectList()
                    .block(Duration.ofSeconds(5));
            assertNotNull(responses);
            SipResponse ok = responses.get(1);
            client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

            byte[] buf = new byte[1500];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            rtpReceiver.receive(packet);

            byte[] raw = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(), raw, 0, packet.getLength());
            net.pilgrim.sip.rtp.RtpPacket rtpPacket = net.pilgrim.sip.rtp.RtpPacket.parse(raw);
            assertEquals(0, rtpPacket.getPayloadType());
            assertEquals(160, rtpPacket.getPayload().length, "Expected one 20ms G.711 frame");
        }
    }

    @Test
    void testDynamicRtpPortAllocatedAndReleasedOnBye() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "dynamic-rtp-test-" + UUID.randomUUID();

        try (DatagramSocket rtpReceiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            rtpReceiver.setSoTimeout(3000);
            String sdpOffer = """
                    v=0
                    o=Alice 1020 1020 IN IP4 127.0.0.1
                    s=Offer
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0
                    """.formatted(rtpReceiver.getLocalPort()).replace("\n", "\r\n");

            SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getPort())
                    .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                    .to("<sip:bob@127.0.0.1>")
                    .callId(callId)
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            List<SipResponse> responses = client.sendWithProvisional(invite, serverAddress)
                    .collectList()
                    .block(Duration.ofSeconds(5));
            assertNotNull(responses);
            SipResponse ok = responses.get(1);

            // Verify SDP answer has a dynamic media port allocated by RtpMediaManager
            net.pilgrim.sip.sdp.SdpMessage parsedAnswer = new net.pilgrim.sip.sdp.SdpParser().parse(ok.getBodyAsString());
            int allocatedServerPort = parsedAnswer.findFirstAudioMedia().getPort();
            assertTrue(allocatedServerPort >= 10000 && allocatedServerPort <= 20000,
                    "Allocated port " + allocatedServerPort + " must be within RTP range 10000-20000");
            assertTrue(allocatedServerPort % 2 == 0, "Allocated RTP port must be even");

            // Verify session is active in media manager
            assertTrue(mediaManager.findSession(callId).isPresent());
            assertEquals(allocatedServerPort, mediaManager.findSession(callId).get().getLocalPort());

            // ACK
            client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

            // Verify RTP packet arrived from the server's allocated Netty media port
            byte[] buf = new byte[1500];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            rtpReceiver.receive(packet);
            assertEquals(allocatedServerPort, packet.getPort(), "RTP probe must originate from allocated Netty media port");

            // BYE
            SipResponse byeResponse = client.sendBye(invite, ok, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(byeResponse);
            assertEquals(200, byeResponse.getStatusCode());

            // Verify media session was terminated and port released
            assertTrue(mediaManager.findSession(callId).isEmpty(), "Media session must be cleaned up on BYE");
        }
    }

    @Test
    void testLateOfferAnswerFlowOverUdp() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "test-late-offer-" + UUID.randomUUID();

        SipRequest inviteWithoutOffer = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<sip:bob@127.0.0.1>")
                .callId(callId)
                .build();

        List<SipResponse> responses = client.sendWithProvisional(inviteWithoutOffer, serverAddress)
                .collectList()
                .block(Duration.ofSeconds(5));
        assertNotNull(responses);
        assertEquals(2, responses.size());

        SipResponse ok = responses.get(1);
        assertEquals(200, ok.getStatusCode());
        assertEquals("application/sdp", ok.getContentType());
        String sdpOfferFromServer = ok.getBodyAsString();
        assertTrue(sdpOfferFromServer.contains("m=audio"), "Server should include SDP offer in 200 OK");

        String ackSdpAnswer = """
                v=0
                o=Alice 1001 1001 IN IP4 127.0.0.1
                s=Answer
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 30002 RTP/AVP 0
                a=rtpmap:0 PCMU/8000
                a=recvonly
                """.replace("\n", "\r\n");
        client.sendAck(inviteWithoutOffer, ok, serverAddress, ackSdpAnswer, "application/sdp")
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
    void testRegisterFlow() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest register = SipRequest.builder(SipMethod.REGISTER, "sip:127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=reg123")
                .to("<sip:alice@127.0.0.1>")
                .contact("<sip:alice@127.0.0.1:5070;transport=udp>")
                .build();

        SipResponse response = client.send(register, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertEquals("<sip:alice@127.0.0.1:5070;transport=udp>", response.getContact());
        assertEquals("3600", response.getHeaders().get("Expires"));
        assertEquals("alice", response.getHeaders().get("X-Registered-User"));
        assertEquals("udp", response.getHeaders().get("X-Transport-Param"));
    }

    @Test
    void testRegisterFlowWithExplicitUriParam() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest register = SipRequest.builder(SipMethod.REGISTER, "sip:127.0.0.1:" + server.getPort() + ";transport=tls")
                .from("<sip:charlie@127.0.0.1>;tag=reg456")
                .to("<sip:charlie@127.0.0.1>")
                .build();

        SipResponse response = client.send(register, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertEquals("charlie", response.getHeaders().get("X-Registered-User"));
        assertEquals("tls", response.getHeaders().get("X-Transport-Param"));
    }

    @Test
    void testOptionsFlow() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest options = SipRequest.builder(SipMethod.OPTIONS, "sip:127.0.0.1:" + server.getPort())
                .from("<sip:scanner@127.0.0.1>;tag=opt123")
                .to("<sip:127.0.0.1>")
                .build();

        SipResponse response = client.send(options, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getHeaders().get("Allow"));
        assertTrue(response.getHeaders().get("Allow").contains("INVITE"));
    }

    @Test
    void testMessageFlow() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest message = SipRequest.builder(SipMethod.MESSAGE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=msg123")
                .to("<sip:bob@127.0.0.1>")
                .contentType("text/plain")
                .body("Hello from Micronaut reactive SIP!")
                .build();

        SipResponse response = client.send(message, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
    }

    @Test
    void testUnsupportedMethodReturnsMethodNotAllowed() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest subscribe = SipRequest.builder(SipMethod.SUBSCRIBE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=sub123")
                .to("<sip:bob@127.0.0.1>")
                .build();

        SipResponse response = client.send(subscribe, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(405, response.getStatusCode());
        assertNotNull(response.getHeaders().get("Allow"));
        assertTrue(response.getHeaders().get("Allow").contains("INVITE"));
    }

    @Test
    void testDtmfOverSipInfoMidDialog() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "test-dtmf-call-" + UUID.randomUUID();

        String sdpOffer = """
                v=0
                o=Alice 1000 1000 IN IP4 127.0.0.1
                s=Offer
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 30000 RTP/AVP 0
                """.replace("\n", "\r\n");

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=dtmf-alice")
                .to("<sip:bob@127.0.0.1>")
                .callId(callId)
                .contentType("application/sdp")
                .body(sdpOffer)
                .build();

        // 1. Establish call
        List<SipResponse> responses = client.sendWithProvisional(invite, serverAddress)
                .collectList()
                .block(Duration.ofSeconds(5));
        assertNotNull(responses);
        assertEquals(2, responses.size());
        SipResponse ok = responses.get(1);
        assertEquals(200, ok.getStatusCode());

        client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

        // 2. Send DTMF tones via INFO (RFC 2976 / RFC 6086) with application/dtmf-relay
        char[] digits = {'1', '2', '3', '4', '#'};
        for (char d : digits) {
            DtmfSignal signal = DtmfSignal.of(d, 160);
            SipResponse infoResp = client.sendDtmf(invite, ok, signal, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(infoResp);
            assertEquals(200, infoResp.getStatusCode());
            assertEquals(String.valueOf(d), infoResp.getHeaders().get("X-Received-DTMF"));
        }

        // Verify session accumulated the DTMF digits
        Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
        assertTrue(sessionOpt.isPresent());
        assertEquals("1234#", sessionOpt.get().getAttribute("dtmfDigits"));

        // 3. Terminate call with BYE
        SipResponse byeResponse = client.sendBye(invite, ok, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());
    }

    @Test
    void testDtmfOverSipMessage() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());

        // 1. Send DTMF via MESSAGE with application/dtmf-relay
        DtmfSignal relaySignal = DtmfSignal.of('9', 200);
        SipResponse relayResp = client.sendDtmfMessage("sip:bob@127.0.0.1:" + server.getPort(), relaySignal, serverAddress)
                .block(Duration.ofSeconds(3));
        assertNotNull(relayResp);
        assertEquals(200, relayResp.getStatusCode());
        assertEquals("9", relayResp.getHeaders().get("X-Received-DTMF"));

        // 2. Send DTMF via MESSAGE with application/dtmf
        SipRequest dtmfMsg = SipRequest.builder(SipMethod.MESSAGE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=dtmfplain")
                .to("<sip:bob@127.0.0.1>")
                .callId("dtmf-plain-" + UUID.randomUUID())
                .dtmf(DtmfSignal.of('*'), "application/dtmf")
                .build();
        SipResponse plainResp = client.send(dtmfMsg, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(plainResp);
        assertEquals(200, plainResp.getStatusCode());
        assertEquals("*", plainResp.getHeaders().get("X-Received-DTMF"));
    }

    @Test
    void testMalformedRequestMissingMandatoryHeadersReturnsBadRequest() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        // Missing To header
        SipRequest malformed = SipRequest.builder(SipMethod.OPTIONS, "sip:127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=opt123")
                .callId("test-call-id")
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=z9hG4bKmalformed;rport")
                .build();
        // Remove To header directly to simulate malformed request
        malformed.getHeaders().remove("To");

        SipResponse response = client.send(malformed, serverAddress, false).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(400, response.getStatusCode());
    }

    @Test
    void testUnsupportedRequireReturnsBadExtension() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest options = SipRequest.builder(SipMethod.OPTIONS, "sip:127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=opt123")
                .to("<sip:127.0.0.1>")
                .callId("test-call-id-require")
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=z9hG4bKrequire;rport")
                .build();
        options.getHeaders().set("Require", "sec-agree, gin");

        SipResponse response = client.send(options, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(420, response.getStatusCode());
        assertNotNull(response.getHeaders().get("Unsupported"));
        assertTrue(response.getHeaders().get("Unsupported").contains("sec-agree"));
    }

    @Test
    void testCancelPendingInviteFlow() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "cancel-integration-" + UUID.randomUUID();

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<sip:bob@127.0.0.1>")
                .callId(callId)
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=z9hG4bK-cancel-" + UUID.randomUUID().toString().substring(0, 8) + ";rport")
                .build();

        List<SipResponse> responses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ringingLatch = new CountDownLatch(1);
        CountDownLatch finalLatch = new CountDownLatch(1);

        client.sendWithProvisional(invite, serverAddress, false)
                .subscribe(resp -> {
                    responses.add(resp);
                    if (resp.getStatusCode() == 180) {
                        ringingLatch.countDown();
                    }
                    if (resp.isFinal()) {
                        finalLatch.countDown();
                    }
                });

        try {
            assertTrue(ringingLatch.await(3, TimeUnit.SECONDS), "Expected 180 Ringing from server");
        } catch (InterruptedException e) {
            fail(e);
        }

        SipResponse cancelResponse = client.sendCancel(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(cancelResponse);
        assertEquals(200, cancelResponse.getStatusCode(), "CANCEL should receive 200 OK");

        try {
            assertTrue(finalLatch.await(3, TimeUnit.SECONDS), "Expected final response to INVITE");
        } catch (InterruptedException e) {
            fail(e);
        }

        assertEquals(2, responses.size());
        assertEquals(180, responses.get(0).getStatusCode());
        assertEquals(487, responses.get(1).getStatusCode(), "INVITE should receive 487 Request Terminated");

        Optional<SipSession> session = sessionManager.findSession(callId);
        assertTrue(session.isPresent());
        assertEquals(SipSession.State.TERMINATED, session.get().getState());
    }

    @Test
    void testCancelUnknownTransactionReturns481() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest orphanInvite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=orphan123")
                .to("<sip:bob@127.0.0.1>")
                .callId("orphan-call-id")
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=z9hG4bK-orphan-1;rport")
                .build();

        SipResponse response = client.sendCancel(orphanInvite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(481, response.getStatusCode());
    }

    @Test
    void testNetworkAuto100TryingOverUdp() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "slow-trying-call-" + UUID.randomUUID();

        SipRequest slowInvite = SipRequest.builder(SipMethod.INVITE, "sip:slow@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<sip:slow@127.0.0.1>")
                .callId(callId)
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=z9hG4bK-slow-" + UUID.randomUUID().toString().substring(0, 8) + ";rport")
                .build();

        // The "slow" route in CallController delays 350ms before emitting 200 OK without sending 180 Ringing.
        // The server auto 100 Trying timer triggers after 200ms and emits 100 Trying over UDP.
        Flux<SipResponse> responseStream = client.sendWithProvisional(slowInvite, serverAddress);
        List<SipResponse> responses = responseStream.collectList().block(Duration.ofSeconds(4));

        assertNotNull(responses);
        assertEquals(2, responses.size(), "Expected 100 Trying followed by 200 OK over live UDP network");
        assertEquals(100, responses.get(0).getStatusCode(), "First response should be 100 Trying from timer");
        assertEquals("Trying", responses.get(0).getReasonPhrase());
        assertEquals(200, responses.get(1).getStatusCode(), "Second response should be 200 OK from controller");
    }

    @Test
    void testNetworkClientTimeoutOverUdp() {
        // Send request to an unassigned local port where no UDP server is listening
        InetSocketAddress deadAddress = new InetSocketAddress("127.0.0.1", 59999);
        SipRequest request = SipRequest.builder(SipMethod.OPTIONS, "sip:nobody@127.0.0.1:59999")
                .from("<sip:alice@127.0.0.1>;tag=timeout-tag")
                .to("<sip:nobody@127.0.0.1:59999>")
                .callId("timeout-network-" + UUID.randomUUID())
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=z9hG4bK-timeout-1;rport")
                .build();

        long start = System.currentTimeMillis();
        StepVerifier.create(client.send(request, deadAddress, Duration.ofMillis(150)))
                .expectError(java.util.concurrent.TimeoutException.class)
                .verify(Duration.ofMillis(600));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 140 && elapsed < 550, "Client timeout timer should fire near 150ms (took " + elapsed + "ms)");
    }

    @Test
    void testFirstInviteDroppedRetransmittedSucceeds() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "drop-first-invite-" + UUID.randomUUID();

        // Server filter drops the FIRST incoming INVITE packet for this Call-ID
        AtomicInteger inviteCount = new AtomicInteger(0);
        net.pilgrim.sip.filter.SipServerFilter dropFirstFilter = (req, chain) -> {
            if (req.getMethod() == SipMethod.INVITE && callId.equals(req.getCallId())) {
                if (inviteCount.incrementAndGet() == 1) {
                    // Simulate 100% loss of first packet: do not proceed chain
                    return Flux.empty();
                }
            }
            return chain.proceed(req);
        };
        dispatcher.addFilter(dropFirstFilter);

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<sip:bob@127.0.0.1>")
                .callId(callId)
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=z9hG4bK-drop-" + UUID.randomUUID().toString().substring(0, 8) + ";rport")
                .build();

        // Client Timer A will retransmit INVITE over UDP after T1 (500ms)
        Flux<SipResponse> responseStream = client.sendWithProvisional(invite, serverAddress);
        List<SipResponse> responses = responseStream.collectList().block(Duration.ofSeconds(6));

        assertNotNull(responses);
        assertTrue(responses.size() >= 2, "Expected 180 and 200 OK after retransmission");
        assertTrue(inviteCount.get() >= 2, "Server must have received at least 2 INVITE attempts (1 dropped + 1 retransmission)");
        assertEquals(180, responses.get(0).getStatusCode());
        assertEquals(200, responses.get(1).getStatusCode());
    }

    @Test
    void testDropped200OkUasTimerGRetransmits() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "drop-200ok-" + UUID.randomUUID();

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=tag-" + UUID.randomUUID().toString().substring(0, 8))
                .to("<sip:bob@127.0.0.1>")
                .callId(callId)
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=z9hG4bK-drop200-" + UUID.randomUUID().toString().substring(0, 8) + ";rport")
                .build();

        // Receive provisional and first 200 OK
        List<SipResponse> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch okLatch = new CountDownLatch(1);

        client.sendWithProvisional(invite, serverAddress).subscribe(resp -> {
            received.add(resp);
            if (resp.getStatusCode() == 200) {
                okLatch.countDown();
            }
        });

        assertTrue(okLatch.await(3, TimeUnit.SECONDS), "First 200 OK should arrive");

        // Do NOT send ACK immediately, simulating ACK dropped or 200 OK dropped.
        // Timer G will retransmit 200 OK after T1 (500ms).
        assertTrue(dispatcher.hasPending2xxRetransmission(callId), "Pending 2xx retransmission must be active in Timer G table");

        // Send ACK now
        SipRequest ack = SipRequest.builder(SipMethod.ACK, "sip:bob@127.0.0.1:" + server.getPort())
                .from(invite.getFrom())
                .to(invite.getTo())
                .callId(callId)
                .cseq(1, SipMethod.ACK)
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=" + invite.getBranch())
                .build();
        client.sendOneWay(ack, serverAddress);

        Thread.sleep(100);
        assertFalse(dispatcher.hasPending2xxRetransmission(callId), "Timer G and H must be cancelled upon ACK receipt");
    }

    @Test
    void testDroppedAckServerRetransmits200OkUntilAck() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "drop-ack-" + UUID.randomUUID();

        AtomicInteger ackArrivals = new AtomicInteger(0);
        net.pilgrim.sip.filter.SipServerFilter dropFirstAck = (req, chain) -> {
            if (req.getMethod() == SipMethod.ACK && callId.equals(req.getCallId())) {
                if (ackArrivals.incrementAndGet() == 1) {
                    // Simulate dropped first ACK
                    return Flux.empty();
                }
            }
            return chain.proceed(req);
        };
        dispatcher.addFilter(dropFirstAck);
        try {
            SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:" + server.getPort())
                    .from("<sip:alice@127.0.0.1>;tag=tag-" + UUID.randomUUID().toString().substring(0, 8))
                    .to("<sip:bob@127.0.0.1>")
                    .callId(callId)
                    .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=z9hG4bK-dropack-" + UUID.randomUUID().toString().substring(0, 8) + ";rport")
                    .build();

            CountDownLatch okLatch = new CountDownLatch(1);
            client.sendWithProvisional(invite, serverAddress).subscribe(resp -> {
                if (resp.getStatusCode() == 200) {
                    okLatch.countDown();
                }
            });
            assertTrue(okLatch.await(3, TimeUnit.SECONDS));

            // First ACK was dropped by server filter
            SipRequest ack1 = SipRequest.builder(SipMethod.ACK, "sip:bob@127.0.0.1:" + server.getPort())
                    .from(invite.getFrom())
                    .to(invite.getTo())
                    .callId(callId)
                    .cseq(1, SipMethod.ACK)
                    .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=" + invite.getBranch())
                    .build();
            client.sendOneWay(ack1, serverAddress);

            Thread.sleep(100);
            // Since first ACK was dropped, Timer G must still be pending
            assertTrue(dispatcher.hasPending2xxRetransmission(callId), "Server must still have pending 2xx retransmission waiting for ACK");

            // Second ACK arrives (retransmitted)
            client.sendOneWay(ack1, serverAddress);
            Thread.sleep(100);
            assertFalse(dispatcher.hasPending2xxRetransmission(callId), "Pending retransmission cleared once ACK reaches server");
            assertEquals(2, ackArrivals.get(), "Server received 2 ACKs (1 dropped + 1 accepted)");
        } finally {
            dispatcher.removeFilter(dropFirstAck);
        }
    }

    @Test
    void testDuplicateByeServerReplays200Ok() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "dup-bye-" + UUID.randomUUID();
        String branch = "z9hG4bK-dupbye-" + UUID.randomUUID().toString().substring(0, 8);

        SipRequest bye1 = SipRequest.builder(SipMethod.BYE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=tag1")
                .to("<sip:bob@127.0.0.1>;tag=tag2")
                .callId(callId)
                .cseq(2, SipMethod.BYE)
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=" + branch + ";rport")
                .build();

        // First BYE -> 200 OK
        SipResponse resp1 = client.send(bye1, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(resp1);
        assertEquals(200, resp1.getStatusCode());

        // Duplicate BYE (retransmission with same branch)
        SipRequest bye2 = SipRequest.builder(SipMethod.BYE, "sip:bob@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=tag1")
                .to("<sip:bob@127.0.0.1>;tag=tag2")
                .callId(callId)
                .cseq(2, SipMethod.BYE)
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=" + branch + ";rport")
                .build();

        SipResponse resp2 = client.send(bye2, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(resp2);
        assertEquals(200, resp2.getStatusCode(), "Server must replay cached 200 OK for duplicate BYE per Timer J (RFC 3261 §17.2.2)");
    }

    @Test
    void testRegisterRetransmittedMidProcessing() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "mid-proc-reg-" + UUID.randomUUID();
        String branch = "z9hG4bK-reg-" + UUID.randomUUID().toString().substring(0, 8);

        SipRequest reg1 = SipRequest.builder(SipMethod.REGISTER, "sip:127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=reg1")
                .to("<sip:alice@127.0.0.1>")
                .callId(callId)
                .cseq(1, SipMethod.REGISTER)
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=" + branch + ";rport")
                .build();

        SipResponse resp1 = client.send(reg1, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(resp1);
        assertEquals(200, resp1.getStatusCode());

        // Retransmitted REGISTER arrives (duplicate in mid-processing or right after)
        SipRequest reg2 = SipRequest.builder(SipMethod.REGISTER, "sip:127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=reg1")
                .to("<sip:alice@127.0.0.1>")
                .callId(callId)
                .cseq(1, SipMethod.REGISTER)
                .via("SIP/2.0/UDP 127.0.0.1:" + server.getPort() + ";branch=" + branch + ";rport")
                .build();

        SipResponse resp2 = client.send(reg2, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(resp2);
        assertEquals(200, resp2.getStatusCode(), "Server must replay cached response for retransmitted REGISTER per Timer J");
    }
}
