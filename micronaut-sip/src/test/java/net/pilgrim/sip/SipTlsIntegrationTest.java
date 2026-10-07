package net.pilgrim.sip;

import net.pilgrim.sip.annotation.OnMessage;
import net.pilgrim.sip.annotation.OnRegister;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.dns.DefaultSipDnsResolver;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.session.SipSessionManager;
import net.pilgrim.sip.transport.SipNettyServer;
import net.pilgrim.sip.transport.SipResponseRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SipTlsIntegrationTest {

    private SipNettyServer server;
    private ReactiveSipClient client;
    private SipResponseRouter responseRouter;
    private DefaultSipDnsResolver dnsResolver;
    private TlsTestController controller;
    private SipServerConfiguration config;

    @SipController
    static class TlsTestController {
        final AtomicReference<SipRequest> lastReceivedRequest = new AtomicReference<>();

        @OnRegister
        public Mono<SipResponse> onRegister(SipRequest req) {
            lastReceivedRequest.set(req);
            SipResponse resp = SipResponse.ok(req);
            resp.getHeaders().set("X-Secure-Transport", req.getTransport() != null ? req.getTransport().name() : "UNKNOWN");
            return Mono.just(resp);
        }

        @OnMessage
        public Mono<SipResponse> onMessage(SipRequest req) {
            lastReceivedRequest.set(req);
            return Mono.just(SipResponse.ok(req));
        }
    }

    @BeforeEach
    void setUp() {
        config = new SipServerConfiguration();
        // Bind to ephemeral ports for test isolation
        config.setUdpPort(0);
        config.setTcpPort(0);
        config.setTlsEnabled(true);
        config.setTlsPort(0);
        config.setTlsHost("127.0.0.1");
        config.setTrustAll(true);

        responseRouter = new SipResponseRouter();
        SipSessionManager sessionManager = new SipSessionManager(config);
        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        controller = new TlsTestController();
        dispatcher.registerController(controller);

        server = new SipNettyServer(config, dispatcher, responseRouter);
        server.start();

        dnsResolver = new DefaultSipDnsResolver();
        client = new ReactiveSipClient(server, responseRouter, config, dnsResolver);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
        if (dnsResolver != null) {
            dnsResolver.shutdown();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testDirectSipOverTlsCommunication() {
        assertTrue(server.isTlsActive(), "SIP TLS server listener must be active");
        int tlsPort = server.getTlsPort();
        assertTrue(tlsPort > 0, "TLS bound port must be allocated");

        InetSocketAddress tlsTarget = new InetSocketAddress("127.0.0.1", tlsPort);

        SipRequest registerReq = SipRequest.builder(SipMethod.REGISTER, "sip:127.0.0.1:" + tlsPort)
                .from("<sip:alice@127.0.0.1:" + tlsPort + ">;tag=tls-tag-1")
                .to("<sip:alice@127.0.0.1:" + tlsPort + ">")
                .callId("tls-test-" + UUID.randomUUID())
                .transport(SipTransport.TLS)
                .build();

        SipResponse response = client.send(registerReq, tlsTarget, Duration.ofSeconds(5)).block();

        assertNotNull(response, "Response must not be null");
        assertEquals(200, response.getStatusCode(), "Should receive 200 OK over TLS");

        SipRequest received = controller.lastReceivedRequest.get();
        assertNotNull(received, "Server controller must have received the request");
        assertNotNull(received.getVia(), "Via header must be populated");
        assertTrue(received.getVia().startsWith("SIP/2.0/TLS"), "Via header must specify TLS transport: " + received.getVia());
    }

    @Test
    void testSipsDnsResolutionAndTlsDispatch() throws Exception {
        assertTrue(server.isTlsActive());
        int tlsPort = server.getTlsPort();

        String testDomain = "secure-sip.internal";
        InetAddress localAddr = InetAddress.getByName("127.0.0.1");
        dnsResolver.addStaticAddress("tls-gateway." + testDomain, localAddr);
        dnsResolver.addStaticNaptr(testDomain, 10, 10, "s", "SIPS+D2T", "", "_sips._tcp." + testDomain);
        dnsResolver.addStaticSrv("_sips._tcp." + testDomain, 10, 100, tlsPort, "tls-gateway." + testDomain);

        // Request with "sips:" scheme to test domain
        SipRequest messageReq = SipRequest.builder(SipMethod.MESSAGE, "sips:bob@" + testDomain)
                .from("<sips:alice@" + testDomain + ">;tag=sips-dns-tag")
                .to("<sips:bob@" + testDomain + ">")
                .callId("sips-dns-" + UUID.randomUUID())
                .body("Secured message via RFC 3263 DNS + TLS", "text/plain")
                .build();

        // Send without explicit destination socket -> should resolve via NAPTR -> SRV -> A over TLS
        SipResponse response = client.send(messageReq, Duration.ofSeconds(5)).block();

        assertNotNull(response);
        assertEquals(200, response.getStatusCode());

        SipRequest received = controller.lastReceivedRequest.get();
        assertNotNull(received);
        assertTrue(received.getVia().contains("SIP/2.0/TLS"));
        assertEquals("Secured message via RFC 3263 DNS + TLS", received.getBodyAsString());
    }

    @Test
    void testSendOneWayOverTls() throws Exception {
        assertTrue(server.isTlsActive());
        int tlsPort = server.getTlsPort();
        InetSocketAddress tlsTarget = new InetSocketAddress("127.0.0.1", tlsPort);

        SipRequest notifyReq = SipRequest.builder(SipMethod.MESSAGE, "sip:127.0.0.1:" + tlsPort)
                .from("<sip:alice@127.0.0.1:" + tlsPort + ">;tag=oneway-tag")
                .to("<sip:bob@127.0.0.1:" + tlsPort + ">")
                .callId("oneway-tls-" + UUID.randomUUID())
                .cseq(1, SipMethod.MESSAGE)
                .via("SIP/2.0/TLS 127.0.0.1:" + tlsPort + ";branch=z9hG4bK" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                .transport(SipTransport.TLS)
                .build();

        client.sendOneWay(notifyReq, tlsTarget);

        // Allow Netty event loop to process the incoming connection and dispatch
        Thread.sleep(300);

        SipRequest received = controller.lastReceivedRequest.get();
        assertNotNull(received, "Server controller should receive one-way message");
        assertEquals(SipMethod.MESSAGE, received.getMethod());
    }
}
