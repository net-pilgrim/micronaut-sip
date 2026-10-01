package net.pilgrim.sip;

import net.pilgrim.sip.annotation.OnInvite;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.annotation.SipError;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.session.SipSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SipErrorAndTryingTest {

    private SipSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new SipSessionManager(Duration.ofMinutes(10), 100);
    }

    @AfterEach
    void tearDown() {
    }

    static class CustomUserNotFoundException extends RuntimeException {
        public CustomUserNotFoundException(String message) {
            super(message);
        }
    }

    @SipController
    static class ErrorThrowingController {
        @OnInvite("notFound")
        public SipResponse throwNotFound(SipRequest req) {
            throw new CustomUserNotFoundException("User not registered in PBX");
        }

        @OnInvite("genericError")
        public SipResponse throwGeneric(SipRequest req) {
            throw new IllegalStateException("Database connection pool exhausted");
        }

        @SipError(CustomUserNotFoundException.class)
        public SipResponse handleNotFound(CustomUserNotFoundException ex, SipRequest req) {
            return req.createResponse(404, "User Not Found: " + ex.getMessage());
        }

        @SipError(IllegalStateException.class)
        public SipResponse handleIllegalState(IllegalStateException ex, SipRequest req) {
            return req.createResponse(503, "Service Unavailable: " + ex.getMessage());
        }
    }

    @Test
    void testSipErrorHierarchyMatching() {
        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager);
        dispatcher.registerController(new ErrorThrowingController());

        // 1. Test CustomUserNotFoundException -> 404
        SipRequest req1 = SipRequest.builder(SipMethod.INVITE, "sip:notFound@biloxi.com")
                .from("sip:alice@atlanta.com")
                .to("sip:notFound@biloxi.com")
                .callId("error-call-1")
                .cseq(1, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-err-1")
                .build();
        req1.setTransport(SipTransport.UDP);

        AtomicReference<SipResponse> resp1 = new AtomicReference<>();
        dispatcher.dispatch(req1, resp1::set);
        assertNotNull(resp1.get());
        assertEquals(404, resp1.get().getStatusCode());
        assertTrue(resp1.get().getReasonPhrase().contains("User Not Found: User not registered in PBX"));

        // 2. Test IllegalStateException -> 503
        SipRequest req2 = SipRequest.builder(SipMethod.INVITE, "sip:genericError@biloxi.com")
                .from("sip:alice@atlanta.com")
                .to("sip:genericError@biloxi.com")
                .callId("error-call-2")
                .cseq(1, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-err-2")
                .build();
        req2.setTransport(SipTransport.UDP);

        AtomicReference<SipResponse> resp2 = new AtomicReference<>();
        dispatcher.dispatch(req2, resp2::set);
        assertNotNull(resp2.get());
        assertEquals(503, resp2.get().getStatusCode());
        assertTrue(resp2.get().getReasonPhrase().contains("Service Unavailable"));
    }

    @SipController
    static class SlowInviteController {
        @OnInvite
        public Mono<SipResponse> slowInvite(SipRequest req) {
            return Mono.just(SipResponse.ok(req)).delayElement(Duration.ofMillis(350));
        }
    }

    @Test
    void testAuto100TryingEmittedWhenProcessingExceedsThreshold() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setAuto100TryingEnabled(true);
        config.setAuto100TryingDelayMs(100); // Trigger after 100ms
        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.registerController(new SlowInviteController());

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com")
                .to("sip:bob@biloxi.com")
                .callId("trying-test-call-1")
                .cseq(1, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-trying-1")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                .build();
        invite.setTransport(SipTransport.UDP);

        List<SipResponse> responses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch finishLatch = new CountDownLatch(2);

        dispatcher.dispatch(invite, resp -> {
            responses.add(resp);
            finishLatch.countDown();
        });

        assertTrue(finishLatch.await(1, TimeUnit.SECONDS), "Should receive both 100 Trying and 200 OK");
        assertEquals(2, responses.size());
        assertEquals(100, responses.get(0).getStatusCode(), "First response should be 100 Trying");
        assertEquals("Trying", responses.get(0).getReasonPhrase());
        assertEquals(200, responses.get(1).getStatusCode(), "Second response should be 200 OK");
    }

    @SipController
    static class FastInviteController {
        @OnInvite
        public SipResponse fastInvite(SipRequest req) {
            return SipResponse.ok(req);
        }
    }

    @Test
    void testAuto100TryingNotEmittedWhenResponseIsFast() throws Exception {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setAuto100TryingEnabled(true);
        config.setAuto100TryingDelayMs(100);
        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config);
        dispatcher.registerController(new FastInviteController());

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com")
                .to("sip:bob@biloxi.com")
                .callId("fast-trying-test")
                .cseq(1, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-fast-1")
                .build();
        invite.setTransport(SipTransport.UDP);

        List<SipResponse> responses = Collections.synchronizedList(new ArrayList<>());
        dispatcher.dispatch(invite, responses::add);

        assertEquals(1, responses.size());
        assertEquals(200, responses.get(0).getStatusCode());

        // Wait beyond delayMs to confirm 100 Trying is not emitted after the fast 200 OK
        Thread.sleep(150);
        assertEquals(1, responses.size(), "No subsequent 100 Trying should be sent once final response emitted");
    }
}
