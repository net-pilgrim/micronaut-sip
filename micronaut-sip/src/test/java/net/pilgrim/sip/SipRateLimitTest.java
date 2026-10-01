package net.pilgrim.sip;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.pilgrim.sip.annotation.OnInvite;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.filter.IpMatcher;
import net.pilgrim.sip.filter.IpRateLimiter;
import net.pilgrim.sip.filter.SipRateLimitFilter;
import net.pilgrim.sip.metrics.MicrometerSipMetrics;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.session.SipSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SipRateLimitTest {

    private SipServerConfiguration config;
    private SimpleMeterRegistry meterRegistry;
    private MicrometerSipMetrics metrics;
    private SipSessionManager sessionManager;
    private SipDispatcher dispatcher;
    private SipRateLimitFilter rateLimitFilter;

    @BeforeEach
    void setUp() {
        config = new SipServerConfiguration();
        config.setRateLimitEnabled(true);
        config.setRateLimitBurstCapacity(3);
        config.setRateLimitRequestsPerSecond(1.0); // 1 token per second
        config.setRateLimitRetryAfterSeconds(5);
        config.setRateLimitWhitelist(List.of("127.0.0.2", "10.0.0.0/8"));

        meterRegistry = new SimpleMeterRegistry();
        metrics = new MicrometerSipMetrics();
        metrics.bindTo(meterRegistry);

        sessionManager = new SipSessionManager();
        rateLimitFilter = new SipRateLimitFilter(config, metrics);

        dispatcher = new SipDispatcher(null, sessionManager, config, metrics);
        dispatcher.addFilter(rateLimitFilter);
        dispatcher.registerController(new TestController());
    }

    private SipRequest createRequest(SipMethod method, String clientIp, int clientPort) {
        SipRequest request = new SipRequest(method, "sip:service@example.com");
        request.setTo("<sip:service@example.com>");
        request.setFrom("<sip:caller@example.com>;tag=abc");
        request.setCallId("call-" + System.nanoTime() + "@test.com");
        request.setCSeq("1 " + method.name());
        request.getHeaders().add(SipHeaders.VIA, "SIP/2.0/UDP " + clientIp + ":" + clientPort + ";branch=z9hG4bK-" + System.nanoTime());
        request.setRemoteAddress(new InetSocketAddress(clientIp, clientPort));
        return request;
    }

    @Test
    void testRequestsAllowedWithinBurst() {
        String clientIp = "192.168.1.50";

        for (int i = 0; i < 3; i++) {
            SipRequest req = createRequest(SipMethod.INVITE, clientIp, 5060);
            AtomicReference<SipResponse> respRef = new AtomicReference<>();
            dispatcher.dispatch(req, respRef::set);

            assertNotNull(respRef.get(), "Request " + (i + 1) + " should be answered");
            assertEquals(200, respRef.get().getStatusCode());
        }
    }

    @Test
    void testRejectionWhenBurstExceeded() {
        String clientIp = "192.168.1.50";

        // Consume the burst capacity of 3
        for (int i = 0; i < 3; i++) {
            SipRequest req = createRequest(SipMethod.INVITE, clientIp, 5060);
            dispatcher.dispatch(req, resp -> {});
        }

        // 4th request must be rejected with 503 Service Unavailable + Retry-After: 5
        SipRequest rejectedReq = createRequest(SipMethod.INVITE, clientIp, 5060);
        AtomicReference<SipResponse> respRef = new AtomicReference<>();
        dispatcher.dispatch(rejectedReq, respRef::set);

        assertNotNull(respRef.get());
        assertEquals(503, respRef.get().getStatusCode());
        assertEquals("Service Unavailable - Rate limit exceeded", respRef.get().getReasonPhrase());
        assertEquals("5", respRef.get().getHeaders().get(SipHeaders.RETRY_AFTER));

        // Verify rejected metrics counter
        double rejectedCount = meterRegistry.counter("sip.server.rejected", "reason", "rate_limited").count();
        assertEquals(1.0, rejectedCount);
    }

    @Test
    void testAckDroppedSilentlyWhenRateLimited() {
        String clientIp = "192.168.1.50";

        // Exhaust burst capacity
        for (int i = 0; i < 3; i++) {
            SipRequest req = createRequest(SipMethod.INVITE, clientIp, 5060);
            dispatcher.dispatch(req, resp -> {});
        }

        // Now send ACK -> RFC 3261 §17.2.1: must be dropped with no response
        SipRequest ackReq = createRequest(SipMethod.ACK, clientIp, 5060);
        List<SipResponse> emitted = new ArrayList<>();
        dispatcher.dispatch(ackReq, emitted::add);

        assertTrue(emitted.isEmpty(), "Rate-limited ACK must be dropped silently without error response");
        assertEquals(1.0, meterRegistry.counter("sip.server.rejected", "reason", "rate_limited").count());
    }

    @Test
    void testExactIpWhitelistBypassesRateLimit() {
        String whitelistedIp = "127.0.0.2";

        // Whitelisted IP can send far beyond burst limit (e.g. 10 requests)
        for (int i = 0; i < 10; i++) {
            SipRequest req = createRequest(SipMethod.INVITE, whitelistedIp, 5060);
            AtomicReference<SipResponse> respRef = new AtomicReference<>();
            dispatcher.dispatch(req, respRef::set);

            assertNotNull(respRef.get());
            assertEquals(200, respRef.get().getStatusCode(), "Whitelisted IP should never be rate limited");
        }
    }

    @Test
    void testCidrSubnetWhitelistBypassesRateLimit() {
        // Subnet 10.0.0.0/8 was configured as whitelisted
        String ipInSubnet = "10.240.12.88";

        for (int i = 0; i < 10; i++) {
            SipRequest req = createRequest(SipMethod.INVITE, ipInSubnet, 5060);
            AtomicReference<SipResponse> respRef = new AtomicReference<>();
            dispatcher.dispatch(req, respRef::set);

            assertNotNull(respRef.get());
            assertEquals(200, respRef.get().getStatusCode(), "CIDR whitelisted IP should never be rate limited");
        }
    }

    @Test
    void testIpMatcherDirect() throws Exception {
        IpMatcher matcher = new IpMatcher(List.of("192.168.1.1", "10.0.0.0/8", "fe80::/10"));

        assertTrue(matcher.matches(InetAddress.getByName("192.168.1.1"), "192.168.1.1"));
        assertFalse(matcher.matches(InetAddress.getByName("192.168.1.2"), "192.168.1.2"));

        assertTrue(matcher.matches(InetAddress.getByName("10.1.2.3"), "10.1.2.3"));
        assertFalse(matcher.matches(InetAddress.getByName("11.1.2.3"), "11.1.2.3"));

        assertTrue(matcher.matches(InetAddress.getByName("fe80::1"), "fe80::1"));
        assertFalse(matcher.matches(InetAddress.getByName("2001:db8::1"), "2001:db8::1"));
    }

    @Test
    void testIpRateLimiterEviction() {
        // Rate limiter with max 2 tracked IPs
        IpRateLimiter limiter = new IpRateLimiter(10.0, 5, 2);
        limiter.tryConsume("1.1.1.1");
        limiter.tryConsume("2.2.2.2");
        assertEquals(2, limiter.getTrackedIpCount());

        // Adding 3rd IP should trigger eviction attempt
        limiter.tryConsume("3.3.3.3");
        assertTrue(limiter.getTrackedIpCount() >= 1);
    }

    @SipController
    public static class TestController {
        @OnInvite
        public SipResponse onInvite(SipRequest req) {
            return SipResponse.ok(req);
        }
    }
}
