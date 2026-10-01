package net.pilgrim.sip;

import net.pilgrim.sip.annotation.OnCancel;
import net.pilgrim.sip.annotation.OnInvite;
import net.pilgrim.sip.annotation.OnRegister;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.filter.TokenBucket;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import net.pilgrim.sip.transport.SipNettyServer;
import net.pilgrim.sip.transport.SipMessageSender;
import net.pilgrim.sip.transport.SipResponseRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification of all asynchronous and reactive timer subsystems:
 * 1. Server Auto 100 Trying Timer (RFC 3261 §17.2.1): emission, fast-path suppression,
 *    cancellation on early provisional responses, cancellation on CANCEL, cancellation on errors.
 * 2. Client Request Timeout Timer: non-blocking timeout handling, cleanup of responseRouter listeners.
 * 3. Token-Bucket Rate Limiter Time Replenishment: nano-level token refilling and burst cap bounds.
 * 4. Session Manager TTL & Touch Timers: dialog expiration, touch extension, and batch cleanup.
 */
public class SipTimerTest {

    private SipSessionManager sessionManager;
    private SipResponseRouter responseRouter;
    private ReactiveSipClient client;

    @BeforeEach
    void setUp() {
        sessionManager = new SipSessionManager(Duration.ofMinutes(10), 100);
        responseRouter = new SipResponseRouter();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
    }

    // =========================================================================
    // 1. Server Auto 100 Trying Timer Tests (RFC 3261 §17.2.1)
    // =========================================================================

    @SipController
    static class SlowInviteController {
        @OnInvite
        public Mono<SipResponse> slowInvite(SipRequest req) {
            return Mono.just(SipResponse.ok(req)).delayElement(Duration.ofMillis(250));
        }
    }

    @Test
    void testAuto100TryingEmittedWhenProcessingExceedsDelay() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setAuto100TryingEnabled(true);
        config.setAuto100TryingDelayMs(80); // 80ms threshold

        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.registerController(new SlowInviteController());

        SipRequest invite = createInvite("trying-delay-exceeded");
        List<SipResponse> responses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);

        dispatcher.dispatch(invite, resp -> {
            responses.add(resp);
            latch.countDown();
        });

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Should receive both 100 Trying and 200 OK");
        assertEquals(2, responses.size());
        assertEquals(100, responses.get(0).getStatusCode(), "First response must be 100 Trying");
        assertEquals("Trying", responses.get(0).getReasonPhrase());
        assertEquals(200, responses.get(1).getStatusCode(), "Second response must be 200 OK");
    }

    @SipController
    static class FastInviteController {
        @OnInvite
        public SipResponse fastInvite(SipRequest req) {
            return SipResponse.ok(req);
        }
    }

    @Test
    void testAuto100TryingNotEmittedWhenFastResponse() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setAuto100TryingEnabled(true);
        config.setAuto100TryingDelayMs(80);

        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.registerController(new FastInviteController());

        SipRequest invite = createInvite("fast-invite-no-trying");
        List<SipResponse> responses = Collections.synchronizedList(new ArrayList<>());

        dispatcher.dispatch(invite, responses::add);

        assertEquals(1, responses.size());
        assertEquals(200, responses.get(0).getStatusCode());

        // Wait beyond timer threshold to ensure 100 Trying is not emitted after 200 OK
        Thread.sleep(120);
        assertEquals(1, responses.size(), "100 Trying must not be emitted when final response is fast");
    }

    @SipController
    static class RingingThenDelayedOkController {
        @OnInvite
        public Flux<SipResponse> inviteWithRinging(SipRequest req) {
            // Emits 180 Ringing immediately (0ms), then 200 OK after 250ms
            return Flux.concat(
                    Mono.just(SipResponse.ringing(req)),
                    Mono.just(SipResponse.ok(req)).delayElement(Duration.ofMillis(250))
            );
        }
    }

    @Test
    void testAuto100TryingCancelledWhenProvisional180RingingEmittedEarly() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setAuto100TryingEnabled(true);
        config.setAuto100TryingDelayMs(80); // Timer scheduled for 80ms

        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.registerController(new RingingThenDelayedOkController());

        SipRequest invite = createInvite("early-ringing-cancels-trying");
        List<SipResponse> responses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);

        dispatcher.dispatch(invite, resp -> {
            responses.add(resp);
            latch.countDown();
        });

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Should receive 180 Ringing and 200 OK");
        assertEquals(2, responses.size(), "Should only contain 180 Ringing and 200 OK (no 100 Trying)");
        assertEquals(180, responses.get(0).getStatusCode(), "First response must be 180 Ringing");
        assertEquals(200, responses.get(1).getStatusCode(), "Second response must be 200 OK");

        for (SipResponse resp : responses) {
            assertNotEquals(100, resp.getStatusCode(), "100 Trying timer should have been cancelled by 180 Ringing");
        }
    }

    @SipController
    static class LongPendingInviteController {
        @OnInvite
        public Mono<SipResponse> longPending(SipRequest req) {
            return Mono.just(SipResponse.ok(req)).delayElement(Duration.ofMillis(400));
        }

        @OnCancel
        public void onCancel(SipRequest req) {
            // Teardown
        }
    }

    @Test
    void testAuto100TryingCancelledWhenCancelArrivesBeforeTimerFires() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setAuto100TryingEnabled(true);
        config.setAuto100TryingDelayMs(100); // 100ms threshold

        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.registerController(new LongPendingInviteController());

        String callId = "cancel-before-trying-timer-" + UUID.randomUUID();
        String branch = "z9hG4bK-cancel-timer-1";

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com")
                .to("sip:bob@biloxi.com")
                .callId(callId)
                .cseq(1, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=" + branch)
                .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                .build();
        invite.setTransport(SipTransport.UDP);

        List<SipResponse> inviteResponses = Collections.synchronizedList(new ArrayList<>());
        dispatcher.dispatch(invite, inviteResponses::add);

        // Cancel after 30ms (well before 100ms trying timer fires)
        Thread.sleep(30);

        SipRequest cancel = SipRequest.builder(SipMethod.CANCEL, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com")
                .to("sip:bob@biloxi.com")
                .callId(callId)
                .cseq(1, SipMethod.CANCEL)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=" + branch)
                .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                .build();
        cancel.setTransport(SipTransport.UDP);

        List<SipResponse> cancelResponses = Collections.synchronizedList(new ArrayList<>());
        dispatcher.dispatch(cancel, cancelResponses::add);

        // Wait past the 100ms trying timer threshold and the 400ms controller delay
        Thread.sleep(200);

        // Verify CANCEL received 200 OK
        assertEquals(1, cancelResponses.size(), "CANCEL should receive exactly 1 response");
        assertEquals(200, cancelResponses.get(0).getStatusCode(), "CANCEL must receive 200 OK");

        // Verify INVITE received 487 Request Terminated and NO 100 Trying
        assertEquals(1, inviteResponses.size(), "INVITE should only receive 487 Request Terminated");
        assertEquals(487, inviteResponses.get(0).getStatusCode(), "INVITE must receive 487 Request Terminated");

        for (SipResponse resp : inviteResponses) {
            assertNotEquals(100, resp.getStatusCode(), "100 Trying must be suppressed when transaction is cancelled");
        }
    }

    @SipController
    static class ThrowingController {
        @OnInvite
        public SipResponse throwImmediate(SipRequest req) {
            throw new IllegalArgumentException("Invalid user parameters");
        }
    }

    @Test
    void testAuto100TryingCancelledWhenControllerFailsImmediately() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setAuto100TryingEnabled(true);
        config.setAuto100TryingDelayMs(80);

        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.registerController(new ThrowingController());

        SipRequest invite = createInvite("throwing-controller");
        List<SipResponse> responses = Collections.synchronizedList(new ArrayList<>());

        dispatcher.dispatch(invite, responses::add);

        assertEquals(1, responses.size(), "Only error response should be emitted");
        assertEquals(400, responses.get(0).getStatusCode(), "IllegalArgumentException maps to 400 Bad Request");

        // Wait beyond timer threshold to confirm 100 Trying is never emitted after error
        Thread.sleep(120);
        assertEquals(1, responses.size(), "100 Trying must not fire after error dispatch");
    }

    @SipController
    static class AsyncErrorController {
        @OnInvite
        public Mono<SipResponse> asyncError(SipRequest req) {
            return Mono.<SipResponse>error(new IllegalStateException("Async database failure"))
                    .delaySubscription(Duration.ofMillis(30));
        }
    }

    @Test
    void testAuto100TryingCancelledWhenReactiveControllerFailsAsync() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setAuto100TryingEnabled(true);
        config.setAuto100TryingDelayMs(100);

        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.registerController(new AsyncErrorController());

        SipRequest invite = createInvite("async-error-controller");
        List<SipResponse> responses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        dispatcher.dispatch(invite, resp -> {
            responses.add(resp);
            latch.countDown();
        });

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Should receive error response");
        assertEquals(1, responses.size());
        assertEquals(500, responses.get(0).getStatusCode());

        Thread.sleep(150);
        assertEquals(1, responses.size(), "100 Trying must not fire after async reactive error");
    }

    @Test
    void testAuto100TryingDisabledWhenConfiguredFalse() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setAuto100TryingEnabled(false); // Disabled
        config.setAuto100TryingDelayMs(50);

        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.registerController(new SlowInviteController());

        SipRequest invite = createInvite("trying-disabled");
        List<SipResponse> responses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        dispatcher.dispatch(invite, resp -> {
            responses.add(resp);
            latch.countDown();
        });

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Should receive 200 OK");
        assertEquals(1, responses.size(), "100 Trying must be suppressed when auto100TryingEnabled is false");
        assertEquals(200, responses.get(0).getStatusCode());
    }

    // =========================================================================
    // 2. Client Request Timeout Timer Tests (ReactiveSipClient)
    // =========================================================================

    static class StubSipNettyServer extends SipNettyServer {
        public StubSipNettyServer(SipServerConfiguration config) {
            super(config, null, null, null);
        }

        @Override
        public int getUdpPort() {
            return 5060;
        }

        @Override
        public io.netty.channel.ChannelFuture sendUdp(SipMessage message, InetSocketAddress destination) {
            // Drop UDP packet silently to simulate blackhole/packet loss
            return null;
        }
    }

    static class CapturingSipNettyServer extends SipNettyServer {
        private final List<SipMessage> sentMessages = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch sendLatch;

        public CapturingSipNettyServer(SipServerConfiguration config) {
            this(config, 0);
        }

        public CapturingSipNettyServer(SipServerConfiguration config, int expectedSends) {
            super(config, null, null, null);
            this.sendLatch = new CountDownLatch(expectedSends);
        }

        @Override
        public int getUdpPort() {
            return 5060;
        }

        @Override
        public io.netty.channel.ChannelFuture sendUdp(SipMessage message, InetSocketAddress destination) {
            sentMessages.add(message);
            sendLatch.countDown();
            return null;
        }

        public List<SipMessage> getSentMessages() {
            return sentMessages;
        }

        public boolean awaitSends(long timeout, TimeUnit unit) throws InterruptedException {
            return sendLatch.await(timeout, unit);
        }
    }

    @Test
    void testClientUdpRequestTimeoutThrowsTimeoutException() {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setRequestTimeoutMs(100); // 100ms timeout

        StubSipNettyServer stubServer = new StubSipNettyServer(config);
        client = new ReactiveSipClient(stubServer, responseRouter, config);

        SipRequest request = createInvite("timeout-client-call");
        InetSocketAddress destination = new InetSocketAddress("127.0.0.1", 5060);

        long start = System.currentTimeMillis();
        StepVerifier.create(client.send(request, destination))
                .expectError(TimeoutException.class)
                .verify(Duration.ofMillis(500));
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration >= 90 && duration < 400, "Timeout should occur close to configured 100ms (took " + duration + "ms)");
    }

    @Test
    void testClientUdpRequestTimeoutUnregistersResponseRouterListeners() {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setRequestTimeoutMs(80);

        StubSipNettyServer stubServer = new StubSipNettyServer(config);
        client = new ReactiveSipClient(stubServer, responseRouter, config);

        SipRequest request = createInvite("router-cleanup-test");
        InetSocketAddress destination = new InetSocketAddress("127.0.0.1", 5060);

        // Before request, no active listeners
        assertEquals(0, responseRouter.getActiveListenerCount());

        StepVerifier.create(client.send(request, destination))
                .expectError(TimeoutException.class)
                .verify(Duration.ofMillis(500));

        // After timeout triggers, sink.onDispose() must clean up all registered listeners
        assertEquals(0, responseRouter.getActiveListenerCount(),
                "Client timeout must unregister transaction listeners from SipResponseRouter (zero listener leaks)");
    }

    @Test
    void testClientPerRequestCustomTimeoutOverride() {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setRequestTimeoutMs(5000); // Default 5s timeout

        StubSipNettyServer stubServer = new StubSipNettyServer(config);
        client = new ReactiveSipClient(stubServer, responseRouter, config);

        SipRequest request = createInvite("custom-timeout-override");
        InetSocketAddress destination = new InetSocketAddress("127.0.0.1", 5060);

        // Override with custom 100ms timeout
        long start = System.currentTimeMillis();
        StepVerifier.create(client.send(request, destination, Duration.ofMillis(100)))
                .expectError(TimeoutException.class)
                .verify(Duration.ofMillis(500));
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration >= 90 && duration < 400, "Should respect per-request timeout override of 100ms (took " + duration + "ms)");
        assertEquals(0, responseRouter.getActiveListenerCount(), "Router listeners must be cleaned up");
    }

    @Test
    void testClientSendWithProvisionalTimeoutThrowsTimeoutException() {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setRequestTimeoutMs(100);

        StubSipNettyServer stubServer = new StubSipNettyServer(config);
        client = new ReactiveSipClient(stubServer, responseRouter, config);

        SipRequest request = createInvite("provisional-stream-timeout");
        InetSocketAddress destination = new InetSocketAddress("127.0.0.1", 5060);

        StepVerifier.create(client.sendWithProvisional(request, destination))
                .expectError(TimeoutException.class)
                .verify(Duration.ofMillis(500));

        assertEquals(0, responseRouter.getActiveListenerCount());
    }

    // =========================================================================
    // 3. TokenBucket Time-Based Replenishment Tests
    // =========================================================================

    @Test
    void testTokenBucketReplenishmentOverTime() throws InterruptedException {
        // Capacity: 5 tokens, refill rate: 10 tokens/sec -> 1 token per 100ms
        TokenBucket bucket = new TokenBucket(5.0, 10.0);

        // Exhaust all 5 tokens
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryConsume(), "Token " + (i + 1) + " should be consumed");
        }
        assertFalse(bucket.tryConsume(), "Bucket should be completely depleted");

        // Wait 120ms (enough for ~1.2 tokens to be replenished)
        Thread.sleep(120);

        assertTrue(bucket.tryConsume(), "At least 1 token should be replenished after 120ms");
        assertTrue(bucket.getAvailableTokens() < 1.0, "Subsequent token should be unavailable immediately");
    }

    @Test
    void testTokenBucketCapsAtCapacityAfterLongIdle() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(5.0, 50.0);

        // Exhaust tokens
        bucket.tryConsume(5.0);
        assertEquals(0.0, bucket.getAvailableTokens(), 0.05);

        // Wait 200ms -> 0.2s * 50 = 10 tokens generated, but capacity is capped at 5
        Thread.sleep(200);

        assertEquals(5.0, bucket.getAvailableTokens(), 0.05, "Replenished tokens must never exceed burst capacity");
    }

    // =========================================================================
    // 4. Session TTL Expiration & Touch Timers
    // =========================================================================

    @Test
    void testSessionTouchResetsExpirationTimer() throws InterruptedException {
        Duration ttl = Duration.ofMillis(100);
        SipSessionManager mgr = new SipSessionManager(ttl, 100);
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 5060);

        String callId = "touch-timer-test-" + UUID.randomUUID();
        SipSession session = mgr.getOrCreateSession(callId, addr);
        assertNotNull(session);

        // Wait 60ms (< 100ms TTL)
        Thread.sleep(60);

        // Touch resets the lastAccessedAt timestamp
        session.touch();

        // Wait another 60ms (120ms from start, but only 60ms since touch)
        Thread.sleep(60);

        // Without touch, it would be expired (120ms > 100ms). With touch, it is still alive (60ms < 100ms)!
        assertFalse(session.isExpired(ttl), "Session must not be expired because touch() reset the timer window");
        assertTrue(mgr.findSession(callId).isPresent(), "Session must remain accessible");

        // Wait 120ms (> 100ms TTL) since the findSession touch
        Thread.sleep(120);
        assertTrue(session.isExpired(ttl), "Session must expire after TTL elapses since last touch");
        assertFalse(mgr.findSession(callId).isPresent(), "Expired session must not be found");
    }

    @Test
    void testEvictExpiredSessionsBatchTimer() throws InterruptedException {
        Duration ttl = Duration.ofMillis(80);
        SipSessionManager mgr = new SipSessionManager(ttl, 100);
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 5060);

        SipSession s1 = mgr.getOrCreateSession("session-1", addr);
        SipSession s2 = mgr.getOrCreateSession("session-2", addr);
        SipSession s3 = mgr.getOrCreateSession("session-3", addr);

        assertEquals(3, mgr.getActiveSessionCount());

        // Wait 50ms and touch only s1
        Thread.sleep(50);
        s1.touch();

        // Wait 50ms (total 100ms for s2, s3; 50ms for s1)
        Thread.sleep(50);

        int evicted = mgr.evictExpiredSessions();
        assertEquals(2, evicted, "s2 and s3 must be evicted as expired");
        assertEquals(1, mgr.getActiveSessionCount(), "Only touched session s1 should remain active");
        assertTrue(mgr.findSession("session-1").isPresent());
    }

    // =========================================================================
    // 5. RFC 3261 Protocol Timers: A, B, D, E, G, H, J
    // =========================================================================

    @Test
    void testTimerBCancelledOn1xxProvisionalResponse() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setTimerBDelayMs(80); // Timer B set to 80ms
        config.setRingTimeoutMs(1000); // Ring timeout 1000ms

        CapturingSipNettyServer stubServer = new CapturingSipNettyServer(config);
        client = new ReactiveSipClient(stubServer, responseRouter, config);

        String callId = "timer-b-cancel-" + UUID.randomUUID();
        SipRequest request = createInvite(callId);
        InetSocketAddress destination = new InetSocketAddress("127.0.0.1", 5060);

        List<SipResponse> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);

        client.sendWithProvisional(request, destination).subscribe(
                resp -> {
                    received.add(resp);
                    latch.countDown();
                },
                error -> fail("Stream should not fail: " + error.getMessage())
        );

        // At 30ms (< 80ms Timer B), route 180 Ringing provisional response
        Thread.sleep(30);
        SipResponse ringing = SipResponse.ringing(request);
        responseRouter.handleResponse(ringing);

        // Sleep for 120ms (total 150ms > 80ms Timer B). If Timer B were still active, it would have timed out!
        Thread.sleep(120);

        // At 150ms, route 200 OK final response
        SipResponse ok = SipResponse.ok(request);
        responseRouter.handleResponse(ok);

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Must receive both 180 Ringing and 200 OK");
        assertEquals(2, received.size());
        assertEquals(180, received.get(0).getStatusCode());
        assertEquals(200, received.get(1).getStatusCode());
    }

    @Test
    void testRingTimeoutExpiresWhenRingingNeverAnswers() {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setTimerBDelayMs(80); // 80ms initial timeout
        config.setRingTimeoutMs(150); // 150ms ring timeout

        CapturingSipNettyServer stubServer = new CapturingSipNettyServer(config);
        client = new ReactiveSipClient(stubServer, responseRouter, config);

        String callId = "ring-timeout-expire-" + UUID.randomUUID();
        SipRequest request = createInvite(callId);
        InetSocketAddress destination = new InetSocketAddress("127.0.0.1", 5060);

        StepVerifier.create(client.sendWithProvisional(request, destination))
                .then(() -> {
                    // Send 180 Ringing at 30ms (< 80ms Timer B)
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException ignored) {}
                    responseRouter.handleResponse(SipResponse.ringing(request));
                })
                .expectNextMatches(r -> r.getStatusCode() == 180)
                // Ring timeout should expire after 150ms without 200 OK
                .expectError(TimeoutException.class)
                .verify(Duration.ofMillis(800));

        assertEquals(0, responseRouter.getActiveListenerCount(), "Listeners must be cleaned up after ring timeout");
    }

    @Test
    void testTimerAClientInviteRetransmissionOnUdp() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setT1Ms(40); // 40ms initial retransmit
        config.setTimerBDelayMs(800);
        config.setClientRetransmitEnabled(true);

        // Expect initial send + at least 2 retransmissions = 3 total sends
        CapturingSipNettyServer stubServer = new CapturingSipNettyServer(config, 3);
        client = new ReactiveSipClient(stubServer, responseRouter, config);

        String callId = "timer-a-retransmit-" + UUID.randomUUID();
        SipRequest request = createInvite(callId);
        InetSocketAddress destination = new InetSocketAddress("127.0.0.1", 5060);

        client.sendWithProvisional(request, destination).subscribe();

        assertTrue(stubServer.awaitSends(600, TimeUnit.MILLISECONDS), "Client should retransmit INVITE at least twice");
        int countBeforeResp = stubServer.getSentMessages().size();
        assertTrue(countBeforeResp >= 3, "Expected at least 3 INVITE sends, got: " + countBeforeResp);

        // Provide 180 Ringing - must cancel Timer A retransmissions immediately
        SipResponse ringing = SipResponse.ringing(request);
        responseRouter.handleResponse(ringing);

        Thread.sleep(150);
        int countAfterResp = stubServer.getSentMessages().size();
        assertEquals(countBeforeResp, countAfterResp, "Timer A retransmissions must cease upon receiving provisional response");
    }

    @Test
    void testTimerEClientNonInviteRetransmissionOnUdp() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setT1Ms(30);
        config.setT2Ms(60);
        config.setTimerFDelayMs(600);
        config.setClientRetransmitEnabled(true);

        CapturingSipNettyServer stubServer = new CapturingSipNettyServer(config, 3);
        client = new ReactiveSipClient(stubServer, responseRouter, config);

        String callId = "timer-e-retransmit-" + UUID.randomUUID();
        SipRequest options = SipRequest.builder(SipMethod.OPTIONS, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com")
                .to("sip:bob@biloxi.com")
                .callId(callId)
                .cseq(1, SipMethod.OPTIONS)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-opt-" + UUID.randomUUID().toString().substring(0, 8))
                .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                .build();
        options.setTransport(SipTransport.UDP);

        client.send(options, new InetSocketAddress("127.0.0.1", 5060)).subscribe();

        assertTrue(stubServer.awaitSends(500, TimeUnit.MILLISECONDS), "Client should retransmit OPTIONS at least twice");
        int count = stubServer.getSentMessages().size();
        assertTrue(count >= 3);

        // Emitting 200 OK stops Timer E
        SipResponse ok = SipResponse.ok(options);
        responseRouter.handleResponse(ok);

        Thread.sleep(120);
        assertEquals(count, stubServer.getSentMessages().size(), "Timer E retransmissions must cease upon response");
    }

    @Test
    void testTimerDClientAbsorbs3xxTo6xxRetransmissionsAndResendsAck() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setT4Ms(300); // Timer D active for 300ms

        CapturingSipNettyServer stubServer = new CapturingSipNettyServer(config);
        client = new ReactiveSipClient(stubServer, responseRouter, config);

        String callId = "timer-d-test-" + UUID.randomUUID();
        SipRequest invite = createInvite(callId);
        InetSocketAddress destination = new InetSocketAddress("127.0.0.1", 5060);

        List<SipResponse> received = Collections.synchronizedList(new ArrayList<>());
        client.send(invite, destination).subscribe(received::add);

        // Route 486 Busy Here
        SipResponse busy = SipResponse.busyHere(invite);
        responseRouter.handleResponse(busy);

        // Application received the 486 Busy Here
        assertEquals(1, received.size());
        assertEquals(486, received.get(0).getStatusCode());

        // Verify initial ACK was sent by client transaction
        Thread.sleep(40);
        long ackCount1 = stubServer.getSentMessages().stream()
                .filter(m -> m instanceof SipRequest && ((SipRequest) m).getMethod() == SipMethod.ACK)
                .count();
        assertEquals(1, ackCount1, "Client should auto-send ACK for 486 Busy Here");

        // Retransmit 486 Busy Here during Timer D
        responseRouter.handleResponse(busy);

        Thread.sleep(40);
        long ackCount2 = stubServer.getSentMessages().stream()
                .filter(m -> m instanceof SipRequest && ((SipRequest) m).getMethod() == SipMethod.ACK)
                .count();
        assertEquals(2, ackCount2, "Client must resend ACK upon duplicate 486 Busy Here during Timer D");
        assertEquals(1, received.size(), "Duplicate 486 response must be absorbed and not emitted downstream");
    }

    @SipController
    static class SimpleInviteController {
        @OnInvite
        public SipResponse answer(SipRequest req) {
            return SipResponse.ok(req);
        }
    }

    @Test
    void testTimerGUasRetransmits200OkUntilAck() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setT1Ms(30); // 30ms interval
        config.setT2Ms(60);
        config.setTimerHDelayMs(500);
        config.setUas2xxRetransmitEnabled(true);

        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.registerController(new SimpleInviteController());

        String callId = "timer-g-test-" + UUID.randomUUID();
        SipRequest invite = createInvite(callId);

        List<SipResponse> responses = Collections.synchronizedList(new ArrayList<>());
        dispatcher.dispatch(invite, responses::add);

        // Initial 200 OK
        assertEquals(1, responses.size());
        assertEquals(200, responses.get(0).getStatusCode());

        // Wait for Timer G to retransmit 200 OK (30ms + 60ms)
        Thread.sleep(120);
        assertTrue(responses.size() >= 3, "Timer G should retransmit 200 OK at least twice (got " + responses.size() + ")");
        assertEquals(1, dispatcher.getPending2xxRetransmissionCount(), "1 pending 2xx retransmission should be active");

        // Now client sends ACK
        SipRequest ack = SipRequest.builder(SipMethod.ACK, invite.getUri())
                .from(invite.getFrom())
                .to(invite.getTo() + ";tag=test-tag")
                .callId(callId)
                .cseq(1, SipMethod.ACK)
                .via(invite.getVia())
                .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                .build();
        ack.setTransport(SipTransport.UDP);

        dispatcher.dispatch(ack, resp -> {});

        assertEquals(0, dispatcher.getPending2xxRetransmissionCount(), "Timer G must be cancelled when ACK arrives");
        int countAfterAck = responses.size();

        Thread.sleep(100);
        assertEquals(countAfterAck, responses.size(), "No further 200 OK retransmissions should occur after ACK");
    }

    @Test
    void testTimerHUasTeardownOnAckTimeout() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setT1Ms(25);
        config.setTimerHDelayMs(100); // 100ms ACK wait
        config.setUas2xxRetransmitEnabled(true);

        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.registerController(new SimpleInviteController());

        String callId = "timer-h-test-" + UUID.randomUUID();
        SipRequest invite = createInvite(callId);

        // Session created in session manager
        SipSession session = sessionManager.getOrCreateSession(callId, new InetSocketAddress("127.0.0.1", 5060));
        session.setState(SipSession.State.CONFIRMED);

        dispatcher.dispatch(invite, resp -> {});
        assertEquals(1, dispatcher.getPending2xxRetransmissionCount());

        // Do NOT send ACK. Wait past 100ms Timer H threshold
        Thread.sleep(160);

        assertEquals(0, dispatcher.getPending2xxRetransmissionCount(), "Timer H should remove pending retransmission");
        assertEquals(SipSession.State.TERMINATED, session.getState(), "Session must transition to TERMINATED on Timer H expiry");
    }

    @SipController
    static class CountingRegisterController {
        final AtomicInteger callCount = new AtomicInteger();

        @OnRegister
        public SipResponse register(SipRequest req) {
            callCount.incrementAndGet();
            return SipResponse.ok(req);
        }
    }

    @Test
    void testTimerJServerNonInviteResponseCacheAndReplay() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setTimerJDelayMs(150); // 150ms cache duration

        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        CountingRegisterController controller = new CountingRegisterController();
        dispatcher.registerController(controller);

        String branch = "z9hG4bK-timer-j-test";
        SipRequest register = SipRequest.builder(SipMethod.REGISTER, "sip:registrar.biloxi.com")
                .from("sip:alice@biloxi.com")
                .to("sip:alice@biloxi.com")
                .callId("timer-j-call-" + UUID.randomUUID())
                .cseq(1, SipMethod.REGISTER)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=" + branch)
                .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                .build();
        register.setTransport(SipTransport.UDP);

        List<SipResponse> responses1 = new ArrayList<>();
        dispatcher.dispatch(register, responses1::add);

        assertEquals(1, responses1.size());
        assertEquals(200, responses1.get(0).getStatusCode());
        assertEquals(1, controller.callCount.get(), "Controller must be called on first request");
        assertEquals(1, dispatcher.getNonInviteCachedResponseCount(), "Response must be cached in Timer J table");

        // Retransmitted duplicate REGISTER with same branch
        List<SipResponse> responses2 = new ArrayList<>();
        dispatcher.dispatch(register, responses2::add);

        assertEquals(1, responses2.size());
        assertEquals(200, responses2.get(0).getStatusCode());
        assertEquals(1, controller.callCount.get(), "Controller must NOT be re-invoked on duplicate request (replayed from Timer J cache)");

        // Wait for Timer J to expire
        Thread.sleep(200);
        assertEquals(0, dispatcher.getNonInviteCachedResponseCount(), "Cache entry must be evicted after Timer J expires");
    }

    @Test
    void testLateAckAfterTimerHDoesNotResurrectSession() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setUas2xxRetransmitEnabled(true);
        config.setTimerHDelayMs(60);

        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.registerController(new SimpleInviteController());

        String callId = "timer-h-late-ack-" + UUID.randomUUID();
        SipRequest invite = createInvite(callId);

        SipSession session = sessionManager.getOrCreateSession(callId, new InetSocketAddress("127.0.0.1", 5060));
        session.setState(SipSession.State.EARLY);

        List<SipMessage> sentMessages = new ArrayList<>();
        SipMessageSender sender = new SipMessageSender() {
            @Override
            public void sendMessage(SipMessage message) {
                sentMessages.add(message);
            }
        };

        dispatcher.dispatch(invite, sender);
        assertEquals(1, dispatcher.getPending2xxRetransmissionCount());

        // Wait for Timer H to expire (60ms + margin)
        Thread.sleep(120);

        assertEquals(0, dispatcher.getPending2xxRetransmissionCount(), "Timer H should remove pending retransmission");
        assertEquals(SipSession.State.TERMINATED, session.getState(), "Session must transition to TERMINATED on Timer H expiry");

        // Verify that an outbound BYE request was dispatched per RFC 3261 §14.1
        boolean byeSent = sentMessages.stream().anyMatch(m -> m.isRequest() && "BYE".equalsIgnoreCase(((SipRequest) m).getMethodName()));
        assertTrue(byeSent, "UAS must send BYE to terminate dialog when Timer H expires without ACK");

        // Late ACK arrives after Timer H has terminated session
        SipRequest lateAck = SipRequest.builder(SipMethod.ACK, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com")
                .to("sip:bob@biloxi.com")
                .callId(callId)
                .cseq(1, SipMethod.ACK)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=" + invite.getBranch())
                .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                .build();
        lateAck.setTransport(SipTransport.UDP);

        assertDoesNotThrow(() -> dispatcher.dispatch(lateAck, sender));
        assertEquals(SipSession.State.TERMINATED, session.getState(), "Late ACK must NOT resurrect terminated session");
        assertEquals(0, dispatcher.getPending2xxRetransmissionCount(), "Pending retransmissions must remain 0");
    }

    @Test
    void testAckVsTimerGRaceCondition() throws Exception {
        int concurrencyRounds = 40;
        for (int i = 0; i < concurrencyRounds; i++) {
            SipServerConfiguration config = new SipServerConfiguration();
            config.setUas2xxRetransmitEnabled(true);
            config.setT1Ms(5); // Very fast retransmissions to maximize race condition window
            config.setT2Ms(20);
            config.setTimerHDelayMs(500);

            SipSessionManager localSm = new SipSessionManager();
            SipDispatcher dispatcher = new SipDispatcher(null, localSm, config);
            dispatcher.registerController(new SimpleInviteController());

            String callId = "ack-g-race-" + i + "-" + UUID.randomUUID();
            SipRequest invite = createInvite(callId);

            List<SipResponse> receivedResponses = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            dispatcher.dispatch(invite, receivedResponses::add);

            // Thread 1: Wait random 1-10ms and dispatch ACK to cancel Timer G
            Thread ackThread = new Thread(() -> {
                try {
                    startLatch.await();
                    Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 10));
                    SipRequest ack = SipRequest.builder(SipMethod.ACK, "sip:bob@biloxi.com")
                            .from("sip:alice@atlanta.com")
                            .to("sip:bob@biloxi.com")
                            .callId(callId)
                            .cseq(1, SipMethod.ACK)
                            .via("SIP/2.0/UDP 127.0.0.1:5060;branch=" + invite.getBranch())
                            .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                            .build();
                    ack.setTransport(SipTransport.UDP);
                    dispatcher.dispatch(ack, resp -> {});
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            // Thread 2: Concurrent query / state check
            Thread checkThread = new Thread(() -> {
                try {
                    startLatch.await();
                    Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 10));
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            ackThread.start();
            checkThread.start();
            startLatch.countDown();

            assertTrue(doneLatch.await(2, TimeUnit.SECONDS));
            // Let any in-flight retransmit tick settle
            Thread.sleep(30);

            assertEquals(0, dispatcher.getPending2xxRetransmissionCount(),
                    "Pending 2xx retransmissions must be cleanly removed despite concurrent Timer G firing");
        }
    }

    @Test
    void testVirtualTimeTimerGAndHPrecision() {
        VirtualTimeScheduler virtualScheduler = VirtualTimeScheduler.create();

        SipServerConfiguration config = new SipServerConfiguration();
        config.setUas2xxRetransmitEnabled(true);
        config.setT1Ms(500);
        config.setT2Ms(4000);
        config.setTimerHDelayMs(32000); // 64 * T1 = 32s

        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.setTimerScheduler(virtualScheduler);
        dispatcher.registerController(new SimpleInviteController());

        String callId = "vtime-test-" + UUID.randomUUID();
        SipRequest invite = createInvite(callId);

        SipSession session = sessionManager.getOrCreateSession(callId, new InetSocketAddress("127.0.0.1", 5060));
        session.setState(SipSession.State.EARLY);

        List<SipMessage> outbox = new ArrayList<>();
        SipMessageSender sender = new SipMessageSender() {
            @Override
            public void sendMessage(SipMessage message) {
                outbox.add(message);
            }
        };

        dispatcher.dispatch(invite, sender);
        assertEquals(1, outbox.size(), "Initial 200 OK should be emitted");
        assertEquals(1, dispatcher.getPending2xxRetransmissionCount());

        // Advance 500ms (T1): Timer G retransmission #1
        virtualScheduler.advanceTimeBy(Duration.ofMillis(500));
        assertEquals(2, outbox.size(), "Timer G must retransmit 200 OK after T1 (500ms)");

        // Advance 1000ms (2*T1): Timer G retransmission #2
        virtualScheduler.advanceTimeBy(Duration.ofMillis(1000));
        assertEquals(3, outbox.size(), "Timer G must retransmit 200 OK after 2*T1 (1000ms)");

        // Advance 2000ms (4*T1): Timer G retransmission #3
        virtualScheduler.advanceTimeBy(Duration.ofMillis(2000));
        assertEquals(4, outbox.size(), "Timer G must retransmit 200 OK after 4*T1 (2000ms)");

        // Advance remainder to reach 32s total: Timer H expires
        virtualScheduler.advanceTimeBy(Duration.ofSeconds(30));
        assertEquals(0, dispatcher.getPending2xxRetransmissionCount(), "Timer H must expire and clear table at 32s");
        assertEquals(SipSession.State.TERMINATED, session.getState(), "Session must be TERMINATED by Timer H");

        boolean byeSent = outbox.stream().anyMatch(m -> m.isRequest() && "BYE".equalsIgnoreCase(((SipRequest) m).getMethodName()));
        assertTrue(byeSent, "Timer H expiry must send outbound BYE per RFC 3261 §14.1");
    }

    @SipController
    static class DelayedInviteController {
        @OnInvite
        public Flux<SipResponse> onInvite(SipRequest req) {
            return Flux.concat(
                    Mono.just(SipResponse.ringing(req)),
                    Mono.just(SipResponse.ok(req)).delayElement(Duration.ofMillis(300))
            );
        }
    }

    @Test
    void testSecondInviteDuringProceedingRejectedWith500AndRetryAfter() {
        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, new SipServerConfiguration());
        dispatcher.registerController(new DelayedInviteController());

        String callId = "proceeding-rejection-" + UUID.randomUUID();
        SipRequest invite1 = SipRequest.builder(SipMethod.INVITE, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com;tag=tag1")
                .to("sip:bob@biloxi.com")
                .callId(callId)
                .cseq(1, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-inv-1")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                .build();
        invite1.setTransport(SipTransport.UDP);

        List<SipResponse> responses1 = new ArrayList<>();
        dispatcher.dispatch(invite1, responses1::add);

        // 180 Ringing received immediately, transaction is in Proceeding
        assertEquals(1, responses1.size());
        assertEquals(180, responses1.get(0).getStatusCode());
        assertEquals(1, dispatcher.getPendingTransactionCount());

        // Second INVITE arrives before final response to first INVITE
        SipRequest invite2 = SipRequest.builder(SipMethod.INVITE, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com;tag=tag1")
                .to("sip:bob@biloxi.com")
                .callId(callId)
                .cseq(2, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-inv-2")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                .build();
        invite2.setTransport(SipTransport.UDP);

        List<SipResponse> responses2 = new ArrayList<>();
        dispatcher.dispatch(invite2, responses2::add);

        // Assert RFC 3261 §14.2 rejection
        assertEquals(1, responses2.size());
        SipResponse err = responses2.get(0);
        assertEquals(500, err.getStatusCode(), "Second INVITE during Proceeding MUST be rejected with 500");
        String retryAfter = err.getHeaders().get(SipHeaders.RETRY_AFTER);
        assertNotNull(retryAfter, "Retry-After header MUST be present on 500 rejection");
        int retryVal = Integer.parseInt(retryAfter);
        assertTrue(retryVal >= 0 && retryVal <= 10, "Retry-After MUST be between 0 and 10 seconds");
    }

    @Test
    void testDuplicateInviteRetransmitsProvisionalResponse() {
        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, new SipServerConfiguration());
        dispatcher.registerController(new DelayedInviteController());

        String callId = "dup-invite-test-" + UUID.randomUUID();
        String branch = "z9hG4bK-branch-dup";
        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com;tag=tag1")
                .to("sip:bob@biloxi.com")
                .callId(callId)
                .cseq(1, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=" + branch)
                .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                .build();
        invite.setTransport(SipTransport.UDP);

        List<SipResponse> responses1 = new ArrayList<>();
        dispatcher.dispatch(invite, responses1::add);
        assertEquals(1, responses1.size());
        assertEquals(180, responses1.get(0).getStatusCode());

        // Duplicate retransmission of same INVITE (same branch) while in Proceeding
        List<SipResponse> responses2 = new ArrayList<>();
        dispatcher.dispatch(invite, responses2::add);
        assertEquals(1, responses2.size(), "Retransmitted INVITE must receive cached provisional response");
        assertEquals(180, responses2.get(0).getStatusCode(), "Provisional response must be replayed per RFC 3261 §17.2.1");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SipRequest createInvite(String callId) {
        SipRequest req = SipRequest.builder(SipMethod.INVITE, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com")
                .to("sip:bob@biloxi.com")
                .callId(callId)
                .cseq(1, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-" + UUID.randomUUID().toString().substring(0, 8))
                .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                .build();
        req.setTransport(SipTransport.UDP);
        return req;
    }
}
