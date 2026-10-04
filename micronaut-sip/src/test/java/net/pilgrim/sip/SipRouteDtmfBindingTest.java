package net.pilgrim.sip;

import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.dtmf.DtmfSignal;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.router.SipRoute;
import net.pilgrim.sip.session.SipSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SipRouteDtmfBindingTest {

    private SipSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new SipSessionManager(new SipServerConfiguration());
    }

    @SipController
    public static class TestDtmfController {
        DtmfSignal lastSignal;
        char lastChar;
        String lastStr;
        DtmfSignal lastUnannotatedSignal;

        @OnInfo
        public void handleInfoSignal(@SipDtmf DtmfSignal signal) {
            this.lastSignal = signal;
        }

        public void handleCharAndStr(@SipDtmf char digit, @SipDtmf String digitStr) {
            this.lastChar = digit;
            this.lastStr = digitStr;
        }

        public void handleUnannotated(DtmfSignal signal) {
            this.lastUnannotatedSignal = signal;
        }

        public void handleRequired(@SipDtmf(required = true) DtmfSignal signal) {
        }
    }

    @Test
    void testDtmfSignalBinding() throws Exception {
        TestDtmfController controller = new TestDtmfController();
        Method method = TestDtmfController.class.getMethod("handleInfoSignal", DtmfSignal.class);
        SipRoute route = new SipRoute(SipMethod.INFO, null, "", controller, method);

        SipRequest request = SipRequest.builder(SipMethod.INFO, "sip:bob@127.0.0.1:5060")
                .from("<sip:alice@127.0.0.1>;tag=test1")
                .to("<sip:bob@127.0.0.1>")
                .callId("dtmf-route-test-1")
                .dtmf(DtmfSignal.of('5', 180, 2))
                .build();

        route.invoke(request, sessionManager);

        assertNotNull(controller.lastSignal);
        assertEquals('5', controller.lastSignal.getDigit());
        assertEquals(180, controller.lastSignal.getDuration());
        assertEquals(2, controller.lastSignal.getVolume());
    }

    @Test
    void testCharAndStringDtmfBinding() throws Exception {
        TestDtmfController controller = new TestDtmfController();
        Method method = TestDtmfController.class.getMethod("handleCharAndStr", char.class, String.class);
        SipRoute route = new SipRoute(SipMethod.INFO, null, "", controller, method);

        SipRequest request = SipRequest.builder(SipMethod.INFO, "sip:bob@127.0.0.1:5060")
                .dtmf(DtmfSignal.of('#'))
                .build();

        route.invoke(request, sessionManager);

        assertEquals('#', controller.lastChar);
        assertEquals("#", controller.lastStr);
    }

    @Test
    void testUnannotatedDtmfSignalBinding() throws Exception {
        TestDtmfController controller = new TestDtmfController();
        Method method = TestDtmfController.class.getMethod("handleUnannotated", DtmfSignal.class);
        SipRoute route = new SipRoute(SipMethod.INFO, null, "", controller, method);

        SipRequest request = SipRequest.builder(SipMethod.INFO, "sip:bob@127.0.0.1:5060")
                .dtmf(DtmfSignal.of('B', 120))
                .build();

        route.invoke(request, sessionManager);

        assertNotNull(controller.lastUnannotatedSignal);
        assertEquals('B', controller.lastUnannotatedSignal.getDigit());
        assertEquals(120, controller.lastUnannotatedSignal.getDuration());
    }

    @Test
    void testRequiredDtmfThrowsWhenMissing() throws Exception {
        TestDtmfController controller = new TestDtmfController();
        Method method = TestDtmfController.class.getMethod("handleRequired", DtmfSignal.class);
        SipRoute route = new SipRoute(SipMethod.INFO, null, "", controller, method);

        SipRequest request = SipRequest.builder(SipMethod.INFO, "sip:bob@127.0.0.1:5060")
                .body("some non-dtmf body")
                .build();

        assertThrows(IllegalArgumentException.class, () -> route.invoke(request, sessionManager));
    }
}
