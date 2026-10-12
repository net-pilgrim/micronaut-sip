package net.pilgrim.sgw.service;

import net.pilgrim.sgw.model.TcapComponent;
import net.pilgrim.sgw.model.TcapComponentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TcapSimulationHandlerTest {

    private final TcapSimulationHandler handler = new TcapSimulationHandler();
    private final TcapDialogueSession mockSession = new TcapDialogueSession(
            "sgw-tx-100", "call-id-1", "client-tx-1", "dummy-map-v3", null, null, null
    );

    @Test
    void testSendRoutingInfoForSMSuccess() {
        TcapComponent invoke = TcapComponent.invoke(1, "sendRoutingInfoForSM", Map.of("msisdn", "33612345678"));
        List<TcapComponent> results = handler.processComponents(List.of(invoke), mockSession);

        assertEquals(1, results.size());
        TcapComponent resp = results.get(0);
        assertEquals(TcapComponentType.RETURN_RESULT_LAST, resp.getType());
        assertEquals(1, resp.getInvokeId());
        assertEquals("sendRoutingInfoForSM", resp.getOperation());
        assertNotNull(resp.getParameters());
        assertTrue(resp.getParameters().containsKey("imsi"));
        assertTrue(resp.getParameters().containsKey("mscNumber"));
    }

    @Test
    void testSendRoutingInfoForSMUnknownSubscriber() {
        TcapComponent invoke = TcapComponent.invoke(2, "sendRoutingInfoForSM", Map.of("msisdn", "33612345000"));
        List<TcapComponent> results = handler.processComponents(List.of(invoke), mockSession);

        assertEquals(1, results.size());
        TcapComponent resp = results.get(0);
        assertEquals(TcapComponentType.RETURN_ERROR, resp.getType());
        assertEquals(2, resp.getInvokeId());
        assertEquals("unknownSubscriber", resp.getErrorCode());
    }

    @Test
    void testProvideSubscriberInfo() {
        TcapComponent invoke = TcapComponent.invoke(3, "provideSubscriberInfo", Map.of("imsi", "208010123456789"));
        List<TcapComponent> results = handler.processComponents(List.of(invoke), mockSession);

        assertEquals(1, results.size());
        TcapComponent resp = results.get(0);
        assertEquals(TcapComponentType.RETURN_RESULT_LAST, resp.getType());
        assertEquals("idle", resp.getParameters().get("subscriberState"));
        assertTrue(resp.getParameters().containsKey("locationInformation"));
    }

    @Test
    void testCheckIMEI() {
        TcapComponent white = TcapComponent.invoke(4, "checkIMEI", Map.of("imei", "354000000000001"));
        assertEquals("whiteListed", handler.processComponent(white, mockSession).getParameters().get("equipmentStatus"));

        TcapComponent black = TcapComponent.invoke(5, "checkIMEI", Map.of("imei", "000012345678901"));
        assertEquals("blackListed", handler.processComponent(black, mockSession).getParameters().get("equipmentStatus"));

        TcapComponent grey = TcapComponent.invoke(6, "checkIMEI", Map.of("imei", "111122223333444"));
        assertEquals("greyListed", handler.processComponent(grey, mockSession).getParameters().get("equipmentStatus"));
    }

    @Test
    void testCustomHandlerRegistration() {
        handler.registerHandler("checkBalance", (comp, session) -> {
            int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
            return TcapComponent.returnResult(id, "checkBalance", Map.of("balanceEur", 42.50, "currency", "EUR"));
        });

        TcapComponent invoke = TcapComponent.invoke(7, "checkBalance", Map.of("account", "ACC-99"));
        TcapComponent resp = handler.processComponent(invoke, mockSession);

        assertNotNull(resp);
        assertEquals(TcapComponentType.RETURN_RESULT_LAST, resp.getType());
        assertEquals(42.50, resp.getParameters().get("balanceEur"));
    }

    @Test
    void testSimulatedErrorParameter() {
        TcapComponent invoke = TcapComponent.invoke(8, "customOp", Map.of("simulateError", "systemFailure"));
        TcapComponent resp = handler.processComponent(invoke, mockSession);

        assertNotNull(resp);
        assertEquals(TcapComponentType.RETURN_ERROR, resp.getType());
        assertEquals("systemFailure", resp.getErrorCode());
    }

    @Test
    void testUnmappedEchoFallback() {
        TcapComponent invoke = TcapComponent.invoke(9, "unknownTelecomOp", Map.of("foo", "bar"));
        TcapComponent resp = handler.processComponent(invoke, mockSession);

        assertNotNull(resp);
        assertEquals(TcapComponentType.RETURN_RESULT_LAST, resp.getType());
        assertEquals("bar", resp.getParameters().get("foo"));
        assertEquals("SUCCESS", resp.getParameters().get("status"));
    }

    @Test
    void testPlayAnnouncementHandler() {
        TcapComponent invoke = TcapComponent.invoke(10, "playAnnouncement", Map.of(
                "messageId", 105,
                "announcementText", "Thank you for calling",
                "duration", 5
        ));
        TcapComponent resp = handler.processComponent(invoke, mockSession);

        assertNotNull(resp);
        assertEquals(TcapComponentType.RETURN_RESULT_LAST, resp.getType());
        assertEquals("specializedResourceReport", resp.getOperation());
        assertEquals("ANNOUNCEMENT_COMPLETED", resp.getParameters().get("status"));
        assertNotNull(resp.getBerHex());
    }

    @Test
    void testPromptAndCollectUserInfoHandler() {
        TcapComponent invoke = TcapComponent.invoke(11, "playAndCollectUserInfo", Map.of(
                "messageId", 202,
                "endOfReplyDigit", "#",
                "simulatedDigits", "4321#"
        ));
        TcapComponent resp = handler.processComponent(invoke, mockSession);

        assertNotNull(resp);
        assertEquals(TcapComponentType.RETURN_RESULT_LAST, resp.getType());
        assertEquals("promptAndCollectUserInformation", resp.getOperation());
        assertEquals("4321#", resp.getParameters().get("digitsResponse"));
        assertNotNull(resp.getBerHex());
    }

    @Test
    void testInitialDpIvrPlayAnnouncementTrigger() {
        TcapComponent idp = TcapComponent.invoke(12, "initialDP", Map.of(
                "serviceKey", 1,
                "calledPartyNumber", "7014455",
                "callingPartyNumber", "33612345678"
        ));
        TcapComponent resp = handler.processComponent(idp, mockSession);

        assertNotNull(resp);
        assertEquals(TcapComponentType.INVOKE, resp.getType());
        assertEquals("playAnnouncement", resp.getOperation());
        assertNotNull(resp.getBerHex());
        assertTrue(resp.getParameters().containsKey("announcementText"));
    }

    @Test
    void testInitialDpIvrPromptAndCollectTrigger() {
        TcapComponent idp = TcapComponent.invoke(13, "initialDP", Map.of(
                "serviceKey", 1,
                "calledPartyNumber", "7029900",
                "callingPartyNumber", "33612345678"
        ));
        TcapComponent resp = handler.processComponent(idp, mockSession);

        assertNotNull(resp);
        assertEquals(TcapComponentType.INVOKE, resp.getType());
        assertEquals("promptAndCollectUserInformation", resp.getOperation());
        assertNotNull(resp.getBerHex());
        assertEquals(4, resp.getParameters().get("maxDigits"));
    }
}
