package net.pilgrim.sgw.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TcapJsonCodecTest {

    private final TcapJsonCodec codec = new TcapJsonCodec();

    @Test
    void testEncodeAndDecodeBeginMessage() {
        TcapComponent invoke = TcapComponent.invoke(1, "sendRoutingInfoForSM", Map.of("msisdn", "33612345678"));
        TcapMessage original = TcapMessage.begin("client-tx-100", "dummy-map-v3", java.util.List.of(invoke));

        String json = codec.encode(original);
        assertNotNull(json);
        assertTrue(json.contains("TC_BEGIN"));
        assertTrue(json.contains("client-tx-100"));
        assertTrue(json.contains("sendRoutingInfoForSM"));

        TcapMessage decoded = codec.decode(json);
        assertEquals(TcapMessageType.TC_BEGIN, decoded.getType());
        assertEquals("client-tx-100", decoded.getOtid());
        assertNotNull(decoded.getDialogue());
        assertEquals("dummy-map-v3", decoded.getDialogue().getApplicationContext());
        assertEquals(1, decoded.getComponents().size());
        TcapComponent comp = decoded.getComponents().get(0);
        assertEquals(TcapComponentType.INVOKE, comp.getType());
        assertEquals(1, comp.getInvokeId());
        assertEquals("sendRoutingInfoForSM", comp.getOperation());
        assertEquals("33612345678", comp.getParameters().get("msisdn"));
    }

    @Test
    void testDecodeMixedCaseAndAliases() {
        // As requested by user: TC_begin, tc_continue, tc_end
        String jsonBegin = """
                {
                  "type": "TC_begin",
                  "otid": "tx-42",
                  "components": [
                    {
                      "type": "invoke",
                      "invokeId": 10,
                      "operation": "provideSubscriberInfo",
                      "parameters": {
                        "imsi": "208010123456789"
                      }
                    }
                  ]
                }
                """;

        TcapMessage msg = codec.decode(jsonBegin);
        assertEquals(TcapMessageType.TC_BEGIN, msg.getType());
        assertEquals("tx-42", msg.getOtid());
        assertEquals(1, msg.getComponents().size());
        assertEquals(TcapComponentType.INVOKE, msg.getComponents().get(0).getType());
        assertEquals("provideSubscriberInfo", msg.getComponents().get(0).getOperation());

        String jsonContinue = """
                {
                  "type": "tc_continue",
                  "otid": "client-42",
                  "dtid": "server-99",
                  "components": []
                }
                """;
        TcapMessage msgCont = codec.decode(jsonContinue);
        assertEquals(TcapMessageType.TC_CONTINUE, msgCont.getType());
        assertEquals("client-42", msgCont.getOtid());
        assertEquals("server-99", msgCont.getDtid());

        String jsonEnd = """
                {
                  "type": "tc_end",
                  "dtid": "server-99"
                }
                """;
        TcapMessage msgEnd = codec.decode(jsonEnd);
        assertEquals(TcapMessageType.TC_END, msgEnd.getType());
        assertEquals("server-99", msgEnd.getDtid());

        String jsonAbort = """
                {
                  "type": "tc_abort",
                  "dtid": "server-99",
                  "abortReason": "unrecognizedTxId"
                }
                """;
        TcapMessage msgAbort = codec.decode(jsonAbort);
        assertEquals(TcapMessageType.TC_ABORT, msgAbort.getType());
        assertEquals("server-99", msgAbort.getDtid());
        assertEquals("unrecognizedTxId", msgAbort.getAbortReason());
    }

    @Test
    void testInvalidJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("   "));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("not a json"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"unknown\":\"field\"}"));
    }

    @Test
    void testAutoDecodePlayAnnouncementBerHex() {
        var paBer = net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodePlayAnnouncement(101, "Hello World", 2, 8, true, true);
        String json = """
                {
                  "type": "tc_continue",
                  "otid": "tx-1",
                  "dtid": "rx-2",
                  "components": [
                    {
                      "type": "invoke",
                      "invokeId": 1,
                      "operation": "playAnnouncement",
                      "berHex": "%s"
                    }
                  ]
                }
                """.formatted(paBer.toHex());

        TcapMessage msg = codec.decode(json);
        assertEquals(1, msg.getComponents().size());
        TcapComponent comp = msg.getComponents().get(0);
        assertEquals("playAnnouncement", comp.getOperation());
        assertNotNull(comp.getParameters());
        assertEquals("Hello World", comp.getParameters().get("announcementText"));
        assertEquals(2, comp.getParameters().get("numberOfLoops"));
        assertEquals(8, comp.getParameters().get("duration"));
        assertEquals(true, comp.getParameters().get("requestAnnouncementComplete"));
    }

    @Test
    void testAutoDecodePromptAndCollectUserInfoBerHex() {
        var pacBer = net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodePromptAndCollectUserInfo(
                201, "Enter PIN", 1, 4, "#", "*", 10, 5, true, true);
        String json = """
                {
                  "type": "tc_continue",
                  "otid": "tx-1",
                  "dtid": "rx-2",
                  "components": [
                    {
                      "type": "invoke",
                      "invokeId": 2,
                      "operation": "playAndCollectUserInfo",
                      "berHex": "%s"
                    }
                  ]
                }
                """.formatted(pacBer.toHex());

        TcapMessage msg = codec.decode(json);
        assertEquals(1, msg.getComponents().size());
        TcapComponent comp = msg.getComponents().get(0);
        assertEquals("playAndCollectUserInfo", comp.getOperation());
        assertNotNull(comp.getParameters());
        assertEquals(1, comp.getParameters().get("minDigits"));
        assertEquals(4, comp.getParameters().get("maxDigits"));
        assertEquals("#", comp.getParameters().get("endOfReplyDigit"));
        assertEquals("Enter PIN", comp.getParameters().get("announcementText"));
    }
}
