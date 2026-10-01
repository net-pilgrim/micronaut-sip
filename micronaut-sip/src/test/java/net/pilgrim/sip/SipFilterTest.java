package net.pilgrim.sip;

import net.pilgrim.sip.annotation.OnBye;
import net.pilgrim.sip.annotation.OnInvite;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.annotation.SipFilter;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.filter.SipFilterChain;
import net.pilgrim.sip.filter.SipServerFilter;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.session.SipSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SipFilterTest {

    private SipDispatcher dispatcher;
    private SipSessionManager sessionManager;
    private TestFilterController controller;

    @BeforeEach
    void setUp() {
        sessionManager = new SipSessionManager();
        dispatcher = new SipDispatcher(null, sessionManager, new SipServerConfiguration(), null);
        controller = new TestFilterController();
        dispatcher.registerController(controller);
    }

    private SipRequest createRequest(SipMethod method, String uri) {
        SipRequest request = new SipRequest(method, uri);
        request.setTo("<sip:bob@example.com>");
        request.setFrom("<sip:alice@example.com>;tag=1234");
        request.setCallId("call-" + System.nanoTime() + "@test.com");
        request.setCSeq("1 " + method.name());
        request.getHeaders().add(SipHeaders.VIA, "SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-" + System.nanoTime());
        return request;
    }

    @Test
    void testFilterExecutionOrder() {
        List<String> executionOrder = new ArrayList<>();

        SipServerFilter filter1 = new SipServerFilter() {
            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public Publisher<SipResponse> doFilter(SipRequest request, SipFilterChain chain) {
                executionOrder.add("filter1-before");
                return Flux.from(chain.proceed(request))
                        .doOnNext(resp -> executionOrder.add("filter1-after"));
            }
        };

        SipServerFilter filter2 = new SipServerFilter() {
            @Override
            public int getOrder() {
                return -10; // lower runs earlier
            }

            @Override
            public Publisher<SipResponse> doFilter(SipRequest request, SipFilterChain chain) {
                executionOrder.add("filter2-before");
                return Flux.from(chain.proceed(request))
                        .doOnNext(resp -> executionOrder.add("filter2-after"));
            }
        };

        dispatcher.addFilter(filter1);
        dispatcher.addFilter(filter2);

        SipRequest req = createRequest(SipMethod.INVITE, "sip:bob@example.com");
        AtomicReference<SipResponse> responseRef = new AtomicReference<>();
        dispatcher.dispatch(req, responseRef::set);

        assertNotNull(responseRef.get());
        assertEquals(200, responseRef.get().getStatusCode());

        // Order: filter2 (-10) before filter1 (10)
        assertEquals(List.of("filter2-before", "filter1-before", "filter1-after", "filter2-after"), executionOrder);
    }

    @Test
    void testRequestMutationInFilter() {
        SipServerFilter mutatingFilter = (request, chain) -> {
            request.getHeaders().set("X-Custom-Tracking", "trace-999");
            return chain.proceed(request);
        };
        dispatcher.addFilter(mutatingFilter);

        SipRequest req = createRequest(SipMethod.INVITE, "sip:bob@example.com");
        dispatcher.dispatch(req, resp -> {});

        assertNotNull(controller.lastReceivedRequest);
        assertEquals("trace-999", controller.lastReceivedRequest.getHeaders().get("X-Custom-Tracking"));
    }

    @Test
    void testResponseMutationInFilter() {
        SipServerFilter responseMutatingFilter = (request, chain) -> Flux.from(chain.proceed(request))
                .map(resp -> {
                    resp.getHeaders().set("X-Added-By-Filter", "true");
                    return resp;
                });
        dispatcher.addFilter(responseMutatingFilter);

        SipRequest req = createRequest(SipMethod.INVITE, "sip:bob@example.com");
        AtomicReference<SipResponse> responseRef = new AtomicReference<>();
        dispatcher.dispatch(req, responseRef::set);

        assertNotNull(responseRef.get());
        assertEquals("true", responseRef.get().getHeaders().get("X-Added-By-Filter"));
    }

    @Test
    void testShortCircuitingFilter() {
        AtomicBoolean controllerInvoked = new AtomicBoolean(false);
        controller.onInviteCallback = () -> controllerInvoked.set(true);

        SipServerFilter authFilter = (request, chain) -> {
            // Block all requests without X-Auth header
            if (!request.getHeaders().contains("X-Auth")) {
                return Mono.just(request.createResponse(403, "Forbidden"));
            }
            return chain.proceed(request);
        };
        dispatcher.addFilter(authFilter);

        SipRequest req = createRequest(SipMethod.INVITE, "sip:bob@example.com");
        AtomicReference<SipResponse> responseRef = new AtomicReference<>();
        dispatcher.dispatch(req, responseRef::set);

        assertNotNull(responseRef.get());
        assertEquals(403, responseRef.get().getStatusCode());
        assertEquals("Forbidden", responseRef.get().getReasonPhrase());
        assertFalse(controllerInvoked.get(), "Controller should not have been invoked when filter short-circuits");
    }

    @Test
    void testMethodSpecificFilterWithAnnotation() {
        @SipFilter(methods = {SipMethod.INVITE})
        class InviteOnlyFilter implements SipServerFilter {
            final AtomicBoolean applied = new AtomicBoolean(false);

            @Override
            public Publisher<SipResponse> doFilter(SipRequest request, SipFilterChain chain) {
                applied.set(true);
                return chain.proceed(request);
            }
        }

        InviteOnlyFilter inviteFilter = new InviteOnlyFilter();
        dispatcher.addFilter(inviteFilter);

        // 1. Send BYE -> filter should be bypassed
        SipRequest byeReq = createRequest(SipMethod.BYE, "sip:bob@example.com");
        dispatcher.dispatch(byeReq, resp -> {});
        assertFalse(inviteFilter.applied.get(), "Filter should not have run for BYE");

        // 2. Send INVITE -> filter should run
        SipRequest inviteReq = createRequest(SipMethod.INVITE, "sip:bob@example.com");
        dispatcher.dispatch(inviteReq, resp -> {});
        assertTrue(inviteFilter.applied.get(), "Filter should have run for INVITE");
    }

    @Test
    void testFilterMultiResponseStreaming() {
        List<Integer> statusesSeenByFilter = new ArrayList<>();

        SipServerFilter streamingTapFilter = (request, chain) -> Flux.from(chain.proceed(request))
                .doOnNext(resp -> statusesSeenByFilter.add(resp.getStatusCode()));
        dispatcher.addFilter(streamingTapFilter);

        controller.streamResponses = true;
        SipRequest req = createRequest(SipMethod.INVITE, "sip:bob@example.com");
        List<SipResponse> clientReceived = new ArrayList<>();
        dispatcher.dispatch(req, clientReceived::add);

        assertEquals(List.of(180, 200), statusesSeenByFilter);
        assertEquals(2, clientReceived.size());
        assertEquals(180, clientReceived.get(0).getStatusCode());
        assertEquals(200, clientReceived.get(1).getStatusCode());
    }

    @SipController
    public static class TestFilterController {
        volatile SipRequest lastReceivedRequest;
        volatile Runnable onInviteCallback;
        volatile boolean streamResponses = false;

        @OnInvite
        public Publisher<SipResponse> handleInvite(SipRequest request) {
            this.lastReceivedRequest = request;
            if (onInviteCallback != null) {
                onInviteCallback.run();
            }
            if (streamResponses) {
                return Flux.just(SipResponse.ringing(request), SipResponse.ok(request));
            }
            return Mono.just(SipResponse.ok(request));
        }

        @OnBye
        public SipResponse handleBye(SipRequest request) {
            return SipResponse.ok(request);
        }
    }
}
