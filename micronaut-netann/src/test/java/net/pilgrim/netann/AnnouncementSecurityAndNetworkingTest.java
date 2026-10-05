package net.pilgrim.netann;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import net.pilgrim.netann.config.NetannConfiguration;
import net.pilgrim.netann.controller.AnnouncementController;
import net.pilgrim.netann.model.AnnouncementParams;
import net.pilgrim.netann.service.AnnouncementAudioLoader;
import net.pilgrim.netann.service.AnnouncementPlayer;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.sip.transport.SipNettyServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = "test")
public class AnnouncementSecurityAndNetworkingTest {

    @Inject
    SipNettyServer server;

    @Inject
    AnnouncementController controller;

    @Inject
    ReactiveSipClient client;

    @Test
    void testHttpFetchingDisabledByDefault() {
        AnnouncementAudioLoader loader = new AnnouncementAudioLoader();
        assertFalse(loader.getConfiguration().isHttpEnabled());

        SecurityException ex = assertThrows(SecurityException.class, () ->
                loader.loadAudio("http://example.com/audio.wav"));
        assertTrue(ex.getMessage().contains("disabled by configuration"));
    }

    @Test
    void testSsrfProtectionBlocksLoopbackAndPrivateAddresses() {
        NetannConfiguration config = new NetannConfiguration();
        config.setHttpEnabled(true);
        config.setSsrfProtectionEnabled(true);
        AnnouncementAudioLoader loader = new AnnouncementAudioLoader(config);

        // 127.0.0.1 loopback
        SecurityException loopbackEx = assertThrows(SecurityException.class, () ->
                loader.loadAudio("http://127.0.0.1/audio.wav"));
        assertTrue(loopbackEx.getMessage().contains("SSRF blocked"));

        // 10.0.0.1 private RFC 1918
        SecurityException priv10Ex = assertThrows(SecurityException.class, () ->
                loader.loadAudio("http://10.0.0.1/audio.wav"));
        assertTrue(priv10Ex.getMessage().contains("SSRF blocked"));

        // 192.168.1.1 private RFC 1918
        SecurityException priv192Ex = assertThrows(SecurityException.class, () ->
                loader.loadAudio("http://192.168.1.1/audio.wav"));
        assertTrue(priv192Ex.getMessage().contains("SSRF blocked"));

        // 172.16.0.1 private RFC 1918
        SecurityException priv172Ex = assertThrows(SecurityException.class, () ->
                loader.loadAudio("http://172.16.0.1/audio.wav"));
        assertTrue(priv172Ex.getMessage().contains("SSRF blocked"));

        // 169.254.169.254 cloud metadata
        SecurityException metaEx = assertThrows(SecurityException.class, () ->
                loader.loadAudio("http://169.254.169.254/latest/meta-data"));
        assertTrue(metaEx.getMessage().contains("SSRF blocked"));
    }

    @Test
    void testAllowedHostsEnforcement() {
        NetannConfiguration config = new NetannConfiguration();
        config.setHttpEnabled(true);
        config.setAllowedHosts(List.of("prompts.trusted-cdn.com"));
        config.setSsrfProtectionEnabled(false); // test host filter isolation
        AnnouncementAudioLoader loader = new AnnouncementAudioLoader(config);

        SecurityException ex = assertThrows(SecurityException.class, () ->
                loader.loadAudio("http://evil-attacker.com/malicious.wav"));
        assertTrue(ex.getMessage().contains("not permitted by allowed-hosts"));
    }

    @Test
    void testPathTraversalBlocked() {
        AnnouncementAudioLoader loader = new AnnouncementAudioLoader();
        SecurityException ex = assertThrows(SecurityException.class, () ->
                loader.loadAudio("/var/netann/prompts/../../etc/passwd"));
        assertTrue(ex.getMessage().contains("Path traversal"));
    }

    @Test
    void testMaxAudioSizeCapEnforcement() {
        NetannConfiguration config = new NetannConfiguration();
        config.setMaxAudioSizeBytes(50); // very small cap: 50 bytes
        AnnouncementAudioLoader loader = new AnnouncementAudioLoader(config);

        // A 1000ms 8kHz tone is 16,000 bytes, which exceeds 50 bytes
        IOException ex = assertThrows(IOException.class, () ->
                loader.loadAudio("prompts/welcome.wav"));
        assertTrue(ex.getMessage().contains("exceeds configured maximum limit"));
    }

    @Test
    void testRepeatForeverDurationCapEnforced() throws Exception {
        AnnouncementParams params = AnnouncementParams.parse(
                SipUri.parse("sip:annc@ms.example.net;play=builtin:tone:440,500;repeat=forever")
        );
        assertTrue(params.isRepeatForever());
        assertEquals(0, params.getDurationMs());

        long customCapMs = 150; // 150 ms cap
        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);

        // Mock RtpMediaSession local mock
        net.pilgrim.sip.rtp.media.RtpMediaManager rtpManager = new net.pilgrim.sip.rtp.media.RtpMediaManager();
        RtpMediaSession session = rtpManager.createSession("test-cap-" + UUID.randomUUID());

        byte[] tone = AnnouncementAudioLoader.generateTone(440, 500);
        AnnouncementPlayer player = new AnnouncementPlayer(
                session,
                tone,
                params,
                customCapMs,
                100,
                () -> {
                    completed.set(true);
                    completeLatch.countDown();
                }
        );

        assertEquals(customCapMs, player.getEffectiveDurationMs());
        player.start();
        assertTrue(player.isRunning());

        // Wait for player to hit duration cap and self-terminate
        assertTrue(completeLatch.await(1000, TimeUnit.MILLISECONDS), "Player must terminate at duration cap");
        assertTrue(completed.get());
        assertFalse(player.isRunning());

        session.close();
        rtpManager.close();
    }

    @Test
    void testContactHeaderUsesServerPortNotCallerRemotePort() {
        int serverPort = server.getPort();
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", serverPort);

        // Ephemeral client port simulation
        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:annc@127.0.0.1:" + serverPort
                        + ";play=builtin:tone:440,100")
                .from("<sip:caller@127.0.0.1:48123>;tag=call-port-test")
                .to("<sip:annc@127.0.0.1>")
                .contact("<sip:caller@127.0.0.1:48123>")
                .callId("test-contact-port-" + UUID.randomUUID())
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());

        String contact = response.getContact();
        assertNotNull(contact);

        // Contact MUST contain the server's listening port (serverPort) and NOT the client's ephemeral port (48123)
        assertTrue(contact.contains(":" + serverPort),
                "Contact header must advertise server's listening port " + serverPort + ", got: " + contact);
        assertFalse(contact.contains(":48123"),
                "Contact header must not leak caller's ephemeral remote port: " + contact);

        // Contact MUST advertise the configured or resolved advertised IP
        String advertisedIp = server.getAdvertisedIp();
        assertTrue(contact.contains(advertisedIp),
                "Contact header must advertise server's advertised IP " + advertisedIp + ", got: " + contact);
    }

    @Test
    void testConcurrencyLimiterRejectsExcessCallsWith503() {
        NetannConfiguration testConfig = new NetannConfiguration();
        testConfig.setMaxAnnouncementsPerIp(2);
        testConfig.setMaxActiveAnnouncements(10);

        AnnouncementController limitedController = new AnnouncementController(
                new net.pilgrim.sip.rtp.media.RtpMediaManager(),
                new AnnouncementAudioLoader(testConfig),
                server,
                null,
                testConfig
        );

        InetSocketAddress remoteAddr = new InetSocketAddress("192.0.2.100", 5060);

        SipRequest req1 = SipRequest.builder(SipMethod.INVITE, "sip:annc@127.0.0.1;play=builtin:tone:440,100")
                .from("<sip:alice@example.com>;tag=t1")
                .to("<sip:annc@example.com>")
                .callId("call-limit-1")
                .build();
        req1.setRemoteAddress(remoteAddr);

        SipRequest req2 = SipRequest.builder(SipMethod.INVITE, "sip:annc@127.0.0.1;play=builtin:tone:440,100")
                .from("<sip:alice@example.com>;tag=t2")
                .to("<sip:annc@example.com>")
                .callId("call-limit-2")
                .build();
        req2.setRemoteAddress(remoteAddr);

        SipRequest req3 = SipRequest.builder(SipMethod.INVITE, "sip:annc@127.0.0.1;play=builtin:tone:440,100")
                .from("<sip:alice@example.com>;tag=t3")
                .to("<sip:annc@example.com>")
                .callId("call-limit-3")
                .build();
        req3.setRemoteAddress(remoteAddr);

        net.pilgrim.sip.session.SipSession session1 = new net.pilgrim.sip.session.SipSession("call-limit-1");
        net.pilgrim.sip.session.SipSession session2 = new net.pilgrim.sip.session.SipSession("call-limit-2");
        net.pilgrim.sip.session.SipSession session3 = new net.pilgrim.sip.session.SipSession("call-limit-3");

        SipResponse resp1 = limitedController.onAnnouncementInvite(req1, "call-limit-1", null, session1).block(Duration.ofSeconds(2));
        assertNotNull(resp1);
        assertEquals(200, resp1.getStatusCode());

        SipResponse resp2 = limitedController.onAnnouncementInvite(req2, "call-limit-2", null, session2).block(Duration.ofSeconds(2));
        assertNotNull(resp2);
        assertEquals(200, resp2.getStatusCode());

        // 3rd call from same IP should exceed maxAnnouncementsPerIp (2) -> 503
        SipResponse resp3 = limitedController.onAnnouncementInvite(req3, "call-limit-3", null, session3).block(Duration.ofSeconds(2));
        assertNotNull(resp3);
        assertEquals(503, resp3.getStatusCode(), "Exceeding per-IP announcement limit must yield 503 Service Unavailable");
        assertEquals("10", resp3.getHeaders().get("Retry-After"), "503 response must include Retry-After header");

        // Clean up call 1 with BYE
        limitedController.onBye(new SipRequest(SipMethod.BYE, req1.getUri()), "call-limit-1", session1).block(Duration.ofSeconds(1));

        // Now 3rd call should succeed
        SipResponse retryResp = limitedController.onAnnouncementInvite(req3, "call-limit-3", null, session3).block(Duration.ofSeconds(2));
        assertNotNull(retryResp);
        assertEquals(200, retryResp.getStatusCode(), "After call cleanup, new call within limit must succeed");
    }
}
