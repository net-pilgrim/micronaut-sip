package net.pilgrim.sip;

import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.model.SipStatus;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.session.SipSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SipExtendedMethodsTest {

    private SipDispatcher dispatcher;
    private ExtendedTestController controller;

    @BeforeEach
    void setUp() {
        SipSessionManager sessionManager = new SipSessionManager(new SipServerConfiguration());
        dispatcher = new SipDispatcher(null, sessionManager, new SipServerConfiguration());
        controller = new ExtendedTestController();
        dispatcher.registerController(controller);
    }

    @SipController
    static class ExtendedTestController {
        String lastPrackCallId;
        String lastPrackRack;
        String lastSubscribeEvent;
        String lastNotifyEvent;
        String lastReferTo;
        String lastUpdateCallId;
        String lastPublishEtag;

        @OnPrack
        public SipResponse onPrack(SipRequest req,
                                  @SipCallId String callId,
                                  @SipHeader("RAck") String rack) {
            this.lastPrackCallId = callId;
            this.lastPrackRack = rack;
            return SipResponse.ok(req);
        }

        @OnSubscribe
        public SipResponse onSubscribe(SipRequest req,
                                      @SipHeader(SipHeaders.EVENT) String event) {
            this.lastSubscribeEvent = event;
            SipResponse resp = SipResponse.accepted(req);
            resp.getHeaders().set(SipHeaders.EXPIRES, "3600");
            resp.getHeaders().set(SipHeaders.EVENT, event);
            return resp;
        }

        @OnNotify
        public Mono<SipResponse> onNotify(SipRequest req,
                                          @SipHeader(SipHeaders.EVENT) String event) {
            this.lastNotifyEvent = event;
            return Mono.just(SipResponse.ok(req));
        }

        @OnRefer
        public SipResponse onRefer(SipRequest req,
                                  @SipHeader("Refer-To") String referTo) {
            this.lastReferTo = referTo;
            return SipResponse.accepted(req);
        }

        @OnUpdate
        public SipResponse onUpdate(SipRequest req,
                                    @SipCallId String callId) {
            this.lastUpdateCallId = callId;
            return SipResponse.ok(req);
        }

        @OnPublish("presence")
        public SipResponse onPublishPresence(SipRequest req,
                                             @SipHeader("SIP-If-Match") String ifMatch) {
            this.lastPublishEtag = ifMatch;
            SipResponse ok = SipResponse.ok(req);
            ok.getHeaders().set(SipHeaders.SIP_ETAG, "dx123abc");
            ok.getHeaders().set(SipHeaders.EXPIRES, "3600");
            return ok;
        }
    }

    private SipRequest.Builder baseRequest(SipMethod method, String uri) {
        return SipRequest.builder(method, uri)
                .from("<sip:alice@example.com>;tag=alice-tag")
                .to("<sip:bob@example.com>")
                .callId("test-call-id-" + method.name())
                .cseq(1, method)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-test-" + method.name());
    }

    @Test
    void testPrackDispatch() {
        SipRequest request = baseRequest(SipMethod.PRACK, "sip:bob@example.com")
                .header(SipHeaders.RACK, "1 1 INVITE")
                .build();

        AtomicReference<SipResponse> responseRef = new AtomicReference<>();
        dispatcher.dispatch(request, responseRef::set);

        assertNotNull(responseRef.get());
        assertEquals(SipStatus.OK, responseRef.get().getStatusCode());
        assertEquals("test-call-id-PRACK", controller.lastPrackCallId);
        assertEquals("1 1 INVITE", controller.lastPrackRack);
    }

    @Test
    void testSubscribeDispatch() {
        SipRequest request = baseRequest(SipMethod.SUBSCRIBE, "sip:bob@example.com")
                .header(SipHeaders.EVENT, "presence")
                .build();

        AtomicReference<SipResponse> responseRef = new AtomicReference<>();
        dispatcher.dispatch(request, responseRef::set);

        assertNotNull(responseRef.get());
        assertEquals(SipStatus.ACCEPTED, responseRef.get().getStatusCode());
        assertEquals("3600", responseRef.get().getHeaders().get(SipHeaders.EXPIRES));
        assertEquals("presence", controller.lastSubscribeEvent);
    }

    @Test
    void testNotifyDispatch() {
        SipRequest request = baseRequest(SipMethod.NOTIFY, "sip:bob@example.com")
                .header(SipHeaders.EVENT, "presence;id=123")
                .build();

        AtomicReference<SipResponse> responseRef = new AtomicReference<>();
        dispatcher.dispatch(request, responseRef::set);

        assertNotNull(responseRef.get());
        assertEquals(SipStatus.OK, responseRef.get().getStatusCode());
        assertEquals("presence;id=123", controller.lastNotifyEvent);
    }

    @Test
    void testReferDispatch() {
        SipRequest request = baseRequest(SipMethod.REFER, "sip:bob@example.com")
                .header(SipHeaders.REFER_TO, "<sip:carol@example.com>")
                .build();

        AtomicReference<SipResponse> responseRef = new AtomicReference<>();
        dispatcher.dispatch(request, responseRef::set);

        assertNotNull(responseRef.get());
        assertEquals(SipStatus.ACCEPTED, responseRef.get().getStatusCode());
        assertEquals("<sip:carol@example.com>", controller.lastReferTo);
    }

    @Test
    void testUpdateDispatch() {
        SipRequest request = baseRequest(SipMethod.UPDATE, "sip:bob@example.com")
                .build();

        AtomicReference<SipResponse> responseRef = new AtomicReference<>();
        dispatcher.dispatch(request, responseRef::set);

        assertNotNull(responseRef.get());
        assertEquals(SipStatus.OK, responseRef.get().getStatusCode());
        assertEquals("test-call-id-UPDATE", controller.lastUpdateCallId);
    }

    @Test
    void testPublishDispatchWithPathMatching() {
        SipRequest request = baseRequest(SipMethod.PUBLISH, "sip:presence@example.com")
                .header(SipHeaders.SIP_IF_MATCH, "etag-xyz")
                .build();

        AtomicReference<SipResponse> responseRef = new AtomicReference<>();
        dispatcher.dispatch(request, responseRef::set);

        assertNotNull(responseRef.get());
        assertEquals(SipStatus.OK, responseRef.get().getStatusCode());
        assertEquals("dx123abc", responseRef.get().getHeaders().get(SipHeaders.SIP_ETAG));
        assertEquals("etag-xyz", controller.lastPublishEtag);
    }

    @Test
    void testAllowedMethodsIncludesAllExtendedMethods() {
        String allowed = dispatcher.getAllowedMethods();
        assertTrue(allowed.contains("PRACK"));
        assertTrue(allowed.contains("SUBSCRIBE"));
        assertTrue(allowed.contains("NOTIFY"));
        assertTrue(allowed.contains("REFER"));
        assertTrue(allowed.contains("UPDATE"));
        assertTrue(allowed.contains("PUBLISH"));
    }
}
