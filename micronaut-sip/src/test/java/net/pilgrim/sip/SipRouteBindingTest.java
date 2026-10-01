package net.pilgrim.sip;

import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipUri;
import net.pilgrim.sip.model.ViaHeader;
import net.pilgrim.sip.router.SipRoute;
import net.pilgrim.sip.session.SipSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class SipRouteBindingTest {

    private SipSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new SipSessionManager(new SipServerConfiguration());
    }

    public static class TestBindingController {
        String lastCallId;
        String lastFrom;
        SipUri lastFromUri;
        String lastTo;
        SipUri lastToUri;
        String lastTransportParam;
        int lastTtlParam;
        int lastExpiresHeader;
        ViaHeader lastVia;

        public void handleMessage(@SipCallId String callId,
                                  @SipFrom String from,
                                  @SipFrom SipUri fromUri,
                                  @SipTo String to,
                                  @SipTo SipUri toUri,
                                  @SipParam("transport") String transport,
                                  @SipParam(value = "ttl", defaultValue = "3600") int ttl,
                                  @SipHeader("Expires") int expires,
                                  ViaHeader via) {
            this.lastCallId = callId;
            this.lastFrom = from;
            this.lastFromUri = fromUri;
            this.lastTo = to;
            this.lastToUri = toUri;
            this.lastTransportParam = transport;
            this.lastTtlParam = ttl;
            this.lastExpiresHeader = expires;
            this.lastVia = via;
        }

        public void handleRequiredParam(@SipParam(value = "token", required = true) String token) {
        }
    }

    @Test
    void testParameterBindingAnnotations() throws Exception {
        TestBindingController controller = new TestBindingController();
        Method method = TestBindingController.class.getMethod("handleMessage",
                String.class, String.class, SipUri.class, String.class, SipUri.class,
                String.class, int.class, int.class, ViaHeader.class);

        SipRoute route = new SipRoute(SipMethod.MESSAGE, null, "", controller, method);

        SipRequest request = SipRequest.builder(SipMethod.MESSAGE, "sip:bob@example.com;transport=tcp")
                .callId("custom-call-id-999")
                .from("\"Alice Smith\" <sip:alice@atlanta.com;user=phone>;tag=12345")
                .to("<sip:bob@example.com>;tag=67890")
                .header("Expires", "60")
                .via("SIP/2.0/UDP pc33.atlanta.com:5060;branch=z9hG4bK-abc")
                .build();

        route.invoke(request, sessionManager);

        assertEquals("custom-call-id-999", controller.lastCallId);
        assertEquals("\"Alice Smith\" <sip:alice@atlanta.com;user=phone>;tag=12345", controller.lastFrom);
        assertNotNull(controller.lastFromUri);
        assertEquals("alice", controller.lastFromUri.getUser());
        assertEquals("atlanta.com", controller.lastFromUri.getHost());

        assertEquals("<sip:bob@example.com>;tag=67890", controller.lastTo);
        assertNotNull(controller.lastToUri);
        assertEquals("bob", controller.lastToUri.getUser());
        assertEquals("example.com", controller.lastToUri.getHost());

        assertEquals("tcp", controller.lastTransportParam);
        assertEquals(3600, controller.lastTtlParam);
        assertEquals(60, controller.lastExpiresHeader);

        assertNotNull(controller.lastVia);
        assertEquals("pc33.atlanta.com", controller.lastVia.getHost());
        assertEquals(5060, controller.lastVia.getPort());
    }

    @Test
    void testRequiredParamMissingThrowsException() throws Exception {
        TestBindingController controller = new TestBindingController();
        Method method = TestBindingController.class.getMethod("handleRequiredParam", String.class);
        SipRoute route = new SipRoute(SipMethod.MESSAGE, null, "", controller, method);

        SipRequest request = SipRequest.builder(SipMethod.MESSAGE, "sip:bob@example.com")
                .build();

        assertThrows(IllegalArgumentException.class, () -> route.invoke(request, sessionManager));
    }
}
