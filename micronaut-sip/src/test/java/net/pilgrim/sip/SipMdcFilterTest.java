package net.pilgrim.sip;

import net.pilgrim.sip.annotation.OnInvite;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.filter.SipMdcFilter;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.session.SipSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SipMdcFilterTest {

    private SipDispatcher dispatcher;
    private SipSessionManager sessionManager;
    private MdcTestController controller;

    @BeforeEach
    void setUp() {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setMdcEnabled(true);
        sessionManager = new SipSessionManager();
        dispatcher = new SipDispatcher(null, sessionManager, config, null);
        dispatcher.addFilter(new SipMdcFilter(config));
        controller = new MdcTestController();
        dispatcher.registerController(controller);
    }

    @Test
    void testMdcPopulatedInsideHandlerAndClearedAfterwards() {
        SipRequest request = new SipRequest(SipMethod.INVITE, "sip:bob@example.com");
        request.setTo("<sip:bob@example.com>");
        request.setFrom("<sip:alice@example.com>;tag=xyz");
        request.setCallId("mdc-call-12345@domain.com");
        request.setCSeq("1 INVITE");
        request.getHeaders().add(SipHeaders.VIA, "SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK-mdc");
        request.setRemoteAddress(new InetSocketAddress("10.0.0.1", 5060));

        AtomicReference<SipResponse> responseRef = new AtomicReference<>();
        dispatcher.dispatch(request, responseRef::set);

        assertNotNull(responseRef.get());
        assertEquals(200, responseRef.get().getStatusCode());

        // Verify MDC was populated during controller invocation
        assertEquals("mdc-call-12345@domain.com", controller.capturedCallId);
        assertEquals("INVITE", controller.capturedMethod);
        assertEquals("1 INVITE", controller.capturedCSeq);
        assertEquals("<sip:alice@example.com>;tag=xyz", controller.capturedFrom);
        assertEquals("<sip:bob@example.com>", controller.capturedTo);
        assertNotNull(controller.capturedRemote);

        // Verify MDC was cleared after pipeline completion
        assertNull(MDC.get(SipMdcFilter.MDC_KEY_CALL_ID));
        assertNull(MDC.get(SipMdcFilter.MDC_KEY_METHOD));
        assertNull(MDC.get(SipMdcFilter.MDC_KEY_CSEQ));
    }

    @SipController
    public static class MdcTestController {
        volatile String capturedCallId;
        volatile String capturedMethod;
        volatile String capturedCSeq;
        volatile String capturedFrom;
        volatile String capturedTo;
        volatile String capturedRemote;

        @OnInvite
        public SipResponse onInvite(SipRequest request) {
            capturedCallId = MDC.get(SipMdcFilter.MDC_KEY_CALL_ID);
            capturedMethod = MDC.get(SipMdcFilter.MDC_KEY_METHOD);
            capturedCSeq = MDC.get(SipMdcFilter.MDC_KEY_CSEQ);
            capturedFrom = MDC.get(SipMdcFilter.MDC_KEY_FROM);
            capturedTo = MDC.get(SipMdcFilter.MDC_KEY_TO);
            capturedRemote = MDC.get(SipMdcFilter.MDC_KEY_REMOTE);
            return SipResponse.ok(request);
        }
    }
}
