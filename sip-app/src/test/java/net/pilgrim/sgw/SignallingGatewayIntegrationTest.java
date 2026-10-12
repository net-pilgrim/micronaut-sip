package net.pilgrim.sgw;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import net.pilgrim.sgw.controller.SignallingGatewayController;
import net.pilgrim.sgw.model.*;
import net.pilgrim.sgw.service.TcapDialogueManager;
import net.pilgrim.sgw.service.TcapDialogueSession;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.transport.SipNettyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class SignallingGatewayIntegrationTest {

    @Inject
    SipNettyServer server;

    @Inject
    ReactiveSipClient client;

    @Inject
    SipDispatcher dispatcher;

    @Inject
    TcapDialogueManager dialogueManager;

    @Inject
    SignallingGatewayController controller;

    @Inject
    TcapJsonCodec jsonCodec;

    @BeforeEach
    @AfterEach
    void cleanup() {
        dispatcher.clearTimerCaches();
        dialogueManager.clearAll();
    }

    @Test
    void testFullTcapDialogueOverSipUdp() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "sgw-call-" + UUID.randomUUID();
        String clientOtid = "client-tx-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. INVITE with TC_BEGIN carrying sendRoutingInfoForSM
        TcapComponent sriInvoke = TcapComponent.invoke(1, "sendRoutingInfoForSM", Map.of("msisdn", "33612345678"));
        TcapMessage beginMsg = TcapMessage.begin(clientOtid, "dummy-map-v3", List.of(sriInvoke));
        String beginJson = jsonCodec.encode(beginMsg);

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:sgw@127.0.0.1:" + server.getPort())
                .from("<sip:client@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<sip:sgw@127.0.0.1>")
                .callId(callId)
                .contentType("application/json")
                .body(beginJson)
                .build();

        SipResponse inviteResponse = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(inviteResponse);
        assertEquals(200, inviteResponse.getStatusCode());
        assertEquals("application/json", inviteResponse.getContentType());
        assertNotNull(inviteResponse.getBodyAsString());

        TcapMessage continueResp = jsonCodec.decode(inviteResponse.getBodyAsString());
        assertEquals(TcapMessageType.TC_CONTINUE, continueResp.getType());
        assertEquals(clientOtid, continueResp.getDtid(), "Destination transaction ID must match client's OTID");
        assertNotNull(continueResp.getOtid(), "Server must allocate and return its own OTID");
        String serverDtid = continueResp.getOtid();

        assertEquals(1, continueResp.getComponents().size());
        TcapComponent sriResult = continueResp.getComponents().get(0);
        assertEquals(TcapComponentType.RETURN_RESULT_LAST, sriResult.getType());
        assertEquals("sendRoutingInfoForSM", sriResult.getOperation());
        assertNotNull(sriResult.getParameters().get("imsi"));
        assertNotNull(sriResult.getParameters().get("mscNumber"));

        // Verify session exists in dialogue manager
        assertTrue(dialogueManager.findByCallId(callId).isPresent());
        TcapDialogueSession session = dialogueManager.findByCallId(callId).get();
        assertEquals(TcapDialogueSession.State.ACTIVE, session.getState());

        // 2. ACK confirming setup
        client.sendAck(invite, inviteResponse, serverAddress).block(Duration.ofSeconds(2));

        // 3. Mid-dialog INFO with TC_CONTINUE carrying provideSubscriberInfo
        TcapComponent psiInvoke = TcapComponent.invoke(2, "provideSubscriberInfo", Map.of("imsi", "208010123456789"));
        TcapMessage continueMsg = TcapMessage.continueDialogue(clientOtid, serverDtid, List.of(psiInvoke));
        String continueJson = jsonCodec.encode(continueMsg);

        SipRequest info = new SipRequest(SipMethod.INFO, invite.getUri());
        info.getHeaders().setCallId(callId);
        info.getHeaders().setFrom(inviteResponse.getFrom());
        info.getHeaders().setTo(inviteResponse.getTo());
        info.getHeaders().setCSeq("2 INFO");
        info.getHeaders().setContentType("application/json");
        info.setBody(continueJson);

        SipResponse infoResponse = client.send(info, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(infoResponse);
        assertEquals(200, infoResponse.getStatusCode());

        TcapMessage infoRespMsg = jsonCodec.decode(infoResponse.getBodyAsString());
        assertEquals(TcapMessageType.TC_CONTINUE, infoRespMsg.getType());
        assertEquals(1, infoRespMsg.getComponents().size());
        TcapComponent psiResult = infoRespMsg.getComponents().get(0);
        assertEquals(TcapComponentType.RETURN_RESULT_LAST, psiResult.getType());
        assertEquals("provideSubscriberInfo", psiResult.getOperation());
        assertEquals("idle", psiResult.getParameters().get("subscriberState"));

        // 4. BYE with TC_END
        TcapMessage endMsg = TcapMessage.end(serverDtid, Collections.emptyList());
        String endJson = jsonCodec.encode(endMsg);

        SipRequest bye = new SipRequest(SipMethod.BYE, invite.getUri());
        bye.getHeaders().setCallId(callId);
        bye.getHeaders().setFrom(inviteResponse.getFrom());
        bye.getHeaders().setTo(inviteResponse.getTo());
        bye.getHeaders().setCSeq("3 BYE");
        bye.getHeaders().setContentType("application/json");
        bye.setBody(endJson);

        SipResponse byeResponse = client.send(bye, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());

        TcapMessage endAck = jsonCodec.decode(byeResponse.getBodyAsString());
        assertEquals(TcapMessageType.TC_END, endAck.getType());

        // Verify dialogue session is terminated
        assertTrue(dialogueManager.findByCallId(callId).isEmpty());
    }

    @Test
    void testMixedCaseTcapPayloads() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "mixed-case-" + UUID.randomUUID();

        // Testing user requirement: "json encoded TC_begin, tc_continue, tc_end paylaods"
        String rawBegin = """
                {
                  "type": "TC_begin",
                  "otid": "client-mix-1",
                  "components": [
                    {
                      "type": "invoke",
                      "invokeId": 1,
                      "operation": "checkIMEI",
                      "parameters": {
                        "imei": "354000000000001"
                      }
                    }
                  ]
                }
                """;

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:sgw@127.0.0.1:" + server.getPort())
                .from("<sip:alice@127.0.0.1>;tag=" + UUID.randomUUID())
                .to("<sip:sgw@127.0.0.1>")
                .callId(callId)
                .contentType("application/json")
                .body(rawBegin)
                .build();

        SipResponse inviteResponse = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(inviteResponse);
        assertEquals(200, inviteResponse.getStatusCode());

        TcapMessage resp = jsonCodec.decode(inviteResponse.getBodyAsString());
        assertEquals(TcapMessageType.TC_CONTINUE, resp.getType());
        String serverDtid = resp.getOtid();

        client.sendAck(invite, inviteResponse, serverAddress).block(Duration.ofSeconds(2));

        // tc_continue payload
        String rawContinue = """
                {
                  "type": "tc_continue",
                  "otid": "client-mix-1",
                  "dtid": "%s",
                  "components": [
                    {
                      "type": "invoke",
                      "invokeId": 2,
                      "operation": "echo",
                      "parameters": {
                        "test": "hello"
                      }
                    }
                  ]
                }
                """.formatted(serverDtid);

        SipRequest info = new SipRequest(SipMethod.INFO, invite.getUri());
        info.getHeaders().setCallId(callId);
        info.getHeaders().setFrom(inviteResponse.getFrom());
        info.getHeaders().setTo(inviteResponse.getTo());
        info.getHeaders().setCSeq("2 INFO");
        info.getHeaders().setContentType("application/json");
        info.setBody(rawContinue);

        SipResponse infoResponse = client.send(info, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(infoResponse);
        assertEquals(200, infoResponse.getStatusCode());

        // tc_end payload
        String rawEnd = """
                {
                  "type": "tc_end",
                  "dtid": "%s"
                }
                """.formatted(serverDtid);

        SipRequest bye = new SipRequest(SipMethod.BYE, invite.getUri());
        bye.getHeaders().setCallId(callId);
        bye.getHeaders().setFrom(inviteResponse.getFrom());
        bye.getHeaders().setTo(inviteResponse.getTo());
        bye.getHeaders().setCSeq("3 BYE");
        bye.getHeaders().setContentType("application/json");
        bye.setBody(rawEnd);

        SipResponse byeResponse = client.send(bye, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());
    }

    @Test
    void testDtidMismatchReturnsAbort() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "mismatch-" + UUID.randomUUID();

        TcapMessage begin = TcapMessage.begin("client-ot-99", List.of(TcapComponent.invoke(1, "echo", Map.of())));
        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:sgw@127.0.0.1:" + server.getPort())
                .from("<sip:caller@127.0.0.1>;tag=" + UUID.randomUUID())
                .to("<sip:sgw@127.0.0.1>")
                .callId(callId)
                .contentType("application/json")
                .body(jsonCodec.encode(begin))
                .build();

        SipResponse inviteResponse = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(inviteResponse);
        client.sendAck(invite, inviteResponse, serverAddress).block(Duration.ofSeconds(2));

        // Send INFO with deliberately mismatched DTID
        TcapMessage badContinue = TcapMessage.continueDialogue("client-ot-99", "wrong-dtid-12345", List.of());
        SipRequest info = new SipRequest(SipMethod.INFO, invite.getUri());
        info.getHeaders().setCallId(callId);
        info.getHeaders().setFrom(inviteResponse.getFrom());
        info.getHeaders().setTo(inviteResponse.getTo());
        info.getHeaders().setCSeq("2 INFO");
        info.getHeaders().setContentType("application/json");
        info.setBody(jsonCodec.encode(badContinue));

        SipResponse infoResponse = client.send(info, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(infoResponse);
        assertEquals(200, infoResponse.getStatusCode());

        TcapMessage abortResp = jsonCodec.decode(infoResponse.getBodyAsString());
        assertEquals(TcapMessageType.TC_ABORT, abortResp.getType());
        assertEquals("unrecognizedTxId", abortResp.getAbortReason());
    }

    @Test
    void testClientInitiatedAbortTerminatesSession() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "client-abort-" + UUID.randomUUID();

        TcapMessage begin = TcapMessage.begin("client-ot-77", List.of());
        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:sgw@127.0.0.1:" + server.getPort())
                .from("<sip:caller@127.0.0.1>;tag=" + UUID.randomUUID())
                .to("<sip:sgw@127.0.0.1>")
                .callId(callId)
                .contentType("application/json")
                .body(jsonCodec.encode(begin))
                .build();

        SipResponse inviteResponse = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(inviteResponse);
        TcapMessage okMsg = jsonCodec.decode(inviteResponse.getBodyAsString());
        String serverDtid = okMsg.getOtid();

        client.sendAck(invite, inviteResponse, serverAddress).block(Duration.ofSeconds(2));

        // Client sends TC_ABORT
        TcapMessage clientAbort = TcapMessage.abort(serverDtid, "userResourceLimitation", "user");
        SipRequest info = new SipRequest(SipMethod.INFO, invite.getUri());
        info.getHeaders().setCallId(callId);
        info.getHeaders().setFrom(inviteResponse.getFrom());
        info.getHeaders().setTo(inviteResponse.getTo());
        info.getHeaders().setCSeq("2 INFO");
        info.getHeaders().setContentType("application/json");
        info.setBody(jsonCodec.encode(clientAbort));

        SipResponse infoResp = client.send(info, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(infoResp);
        assertEquals(200, infoResp.getStatusCode());

        assertTrue(dialogueManager.findByCallId(callId).isEmpty(), "Session must be removed from active manager on abort");
    }

    @Test
    void testMalformedJsonReturnsBadRequest() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "bad-json-" + UUID.randomUUID();

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:sgw@127.0.0.1:" + server.getPort())
                .from("<sip:caller@127.0.0.1>;tag=" + UUID.randomUUID())
                .to("<sip:sgw@127.0.0.1>")
                .callId(callId)
                .contentType("application/json")
                .body("{ this is definitely not valid json }")
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(400, response.getStatusCode());
    }

    @Test
    void testMissingBodyReturnsBadRequest() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "missing-body-" + UUID.randomUUID();

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:sgw@127.0.0.1:" + server.getPort())
                .from("<sip:caller@127.0.0.1>;tag=" + UUID.randomUUID())
                .to("<sip:sgw@127.0.0.1>")
                .callId(callId)
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(400, response.getStatusCode());
    }

    @Test
    void testOptionsQuery() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest options = SipRequest.builder(SipMethod.OPTIONS, "sip:sgw@127.0.0.1:" + server.getPort())
                .from("<sip:caller@127.0.0.1>;tag=" + UUID.randomUUID())
                .to("<sip:sgw@127.0.0.1>")
                .callId("opt-" + UUID.randomUUID())
                .build();

        SipResponse response = client.send(options, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertEquals("Signalling-Gateway-TCAP-over-SIP", response.getHeaders().get("X-Gateway-Role"));
        assertTrue(response.getHeaders().get(SipHeaders.ACCEPT).contains("application/json"));
    }

    @Test
    void testServerInitiatedInfoPush() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "push-test-" + UUID.randomUUID();

        TcapMessage begin = TcapMessage.begin("client-push-1", List.of(TcapComponent.invoke(1, "echo", Map.of())));
        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:sgw@127.0.0.1:" + server.getPort())
                .from("<sip:caller@127.0.0.1>;tag=" + UUID.randomUUID())
                .to("<sip:sgw@127.0.0.1>")
                .callId(callId)
                .contentType("application/json")
                .body(jsonCodec.encode(begin))
                .build();
        invite.getHeaders().set("X-TCAP-Server-Push", "true");

        SipResponse inviteResponse = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(inviteResponse);
        assertEquals(200, inviteResponse.getStatusCode());

        TcapDialogueSession session = dialogueManager.findByCallId(callId).orElseThrow();
        assertEquals(2, session.getHistory().size(), "History should contain incoming BEGIN and response CONTINUE");

        // Send ACK - should trigger the queued server-initiated INFO push
        client.sendAck(invite, inviteResponse, serverAddress).block(Duration.ofSeconds(2));

        // Wait briefly for asynchronous UDP ACK processing and server-initiated INFO emission
        long deadline = System.currentTimeMillis() + 2000;
        while (session.getHistory().size() < 3 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        }

        // Verify history now records the server-pushed message
        assertTrue(session.getHistory().size() >= 3, "History should include server-initiated INFO message");

        // We can also invoke sendServerInfo programmatically
        TcapMessage manualPush = TcapMessage.continueDialogue(session.getServerDtid(), session.getClientOtid(),
                List.of(TcapComponent.invoke(99, "networkInitiatedAlert", Map.of("severity", "HIGH"))));
        boolean sent = controller.sendServerInfo(callId, manualPush);
        assertTrue(sent);
    }

    @Test
    void testFullTcapDialogueOverTcp() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getTcpPort());
        String callId = "tcp-sgw-" + UUID.randomUUID();
        String clientOtid = "client-tcp-" + UUID.randomUUID().toString().substring(0, 8);

        TcapComponent invoke = TcapComponent.invoke(1, "sendRoutingInfoForSM", Map.of("msisdn", "33612345678"));
        TcapMessage begin = TcapMessage.begin(clientOtid, "dummy-map-v3", List.of(invoke));

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:sgw@127.0.0.1:" + server.getTcpPort())
                .from("<sip:tcpclient@127.0.0.1>;tag=" + UUID.randomUUID())
                .to("<sip:sgw@127.0.0.1>")
                .callId(callId)
                .transport(SipTransport.TCP)
                .contentType("application/json")
                .body(jsonCodec.encode(begin))
                .build();

        SipResponse inviteResponse = client.send(invite, serverAddress, SipTransport.TCP).block(Duration.ofSeconds(3));
        assertNotNull(inviteResponse);
        assertEquals(200, inviteResponse.getStatusCode());

        TcapMessage contMsg = jsonCodec.decode(inviteResponse.getBodyAsString());
        String serverDtid = contMsg.getOtid();

        client.sendAck(invite, inviteResponse, serverAddress, null, null, SipTransport.TCP).block(Duration.ofSeconds(2));

        // Send INFO over TCP
        TcapComponent infoInvoke = TcapComponent.invoke(2, "mo-forwardSM", Map.of("sm-RP-UI", "Hello Telecom!"));
        TcapMessage infoMsg = TcapMessage.continueDialogue(clientOtid, serverDtid, List.of(infoInvoke));

        SipRequest info = new SipRequest(SipMethod.INFO, invite.getUri());
        info.setTransport(SipTransport.TCP);
        info.getHeaders().setCallId(callId);
        info.getHeaders().setFrom(inviteResponse.getFrom());
        info.getHeaders().setTo(inviteResponse.getTo());
        info.getHeaders().setCSeq("2 INFO");
        info.getHeaders().setContentType("application/json");
        info.setBody(jsonCodec.encode(infoMsg));

        SipResponse infoResponse = client.send(info, serverAddress, SipTransport.TCP).block(Duration.ofSeconds(3));
        assertNotNull(infoResponse);
        assertEquals(200, infoResponse.getStatusCode());

        TcapMessage infoResp = jsonCodec.decode(infoResponse.getBodyAsString());
        assertEquals("successfulTransfer", infoResp.getComponents().get(0).getParameters().get("deliveryOutcome"));

        // BYE over TCP
        TcapMessage end = TcapMessage.end(serverDtid, List.of());
        SipRequest bye = new SipRequest(SipMethod.BYE, invite.getUri());
        bye.setTransport(SipTransport.TCP);
        bye.getHeaders().setCallId(callId);
        bye.getHeaders().setFrom(inviteResponse.getFrom());
        bye.getHeaders().setTo(inviteResponse.getTo());
        bye.getHeaders().setCSeq("3 BYE");
        bye.getHeaders().setContentType("application/json");
        bye.setBody(jsonCodec.encode(end));

        SipResponse byeResponse = client.send(bye, serverAddress, SipTransport.TCP).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());
    }

    @Test
    void testOutOfDialogMessageFlow() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "msg-sgw-" + UUID.randomUUID();

        TcapComponent comp = TcapComponent.invoke(1, "checkIMEI", Map.of("imei", "000098765432100"));
        TcapMessage uniMsg = TcapMessage.uni("dummy-map-v3", List.of(comp));

        SipRequest msg = SipRequest.builder(SipMethod.MESSAGE, "sip:sgw@127.0.0.1:" + server.getPort())
                .from("<sip:caller@127.0.0.1>;tag=" + UUID.randomUUID())
                .to("<sip:sgw@127.0.0.1>")
                .callId(callId)
                .contentType("application/json")
                .body(jsonCodec.encode(uniMsg))
                .build();

        SipResponse response = client.send(msg, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode());

        TcapMessage resp = jsonCodec.decode(response.getBodyAsString());
        assertEquals(TcapMessageType.TC_UNI, resp.getType());
        assertEquals(1, resp.getComponents().size());
        assertEquals("blackListed", resp.getComponents().get(0).getParameters().get("equipmentStatus"));
    }

    @Test
    void testCamelInitialDpWithAsn1BerPayload() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "camel-" + UUID.randomUUID();
        String clientOtid = "client-camel-1";

        // Create genuine ASN.1 BER InitialDP TLV
        net.pilgrim.sgw.asn1.BerTlv idpTlv = net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodeInitialDP(
                1, "33612345678", "33698765432"
        );
        String berHex = idpTlv.toHex();
        assertNotNull(berHex);

        TcapComponent idpComp = TcapComponent.invoke(1, "initialDP", Map.of(
                "serviceKey", 1,
                "calledPartyNumber", "33612345678"
        ));
        idpComp.setBerHex(berHex);

        TcapMessage begin = TcapMessage.begin(clientOtid, "camel-v2", List.of(idpComp));

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:sgw@127.0.0.1:" + server.getPort())
                .from("<sip:gsm-msc@127.0.0.1>;tag=" + UUID.randomUUID())
                .to("<sip:sgw@127.0.0.1>")
                .callId(callId)
                .contentType("application/json")
                .body(jsonCodec.encode(begin))
                .build();

        SipResponse inviteResponse = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(inviteResponse);
        assertEquals(200, inviteResponse.getStatusCode());

        TcapMessage contMsg = jsonCodec.decode(inviteResponse.getBodyAsString());
        assertEquals(TcapMessageType.TC_CONTINUE, contMsg.getType());
        assertEquals(1, contMsg.getComponents().size());

        TcapComponent achComp = contMsg.getComponents().get(0);
        assertEquals("applyCharging", achComp.getOperation());
        assertNotNull(achComp.getBerHex(), "Response component should include ASN.1 BER payload hex");

        // Verify the gateway's returned BER hex decodes properly as ASN.1 BER
        net.pilgrim.sgw.asn1.BerTlv achDecoded = net.pilgrim.sgw.asn1.BerCodec.decodeHex(achComp.getBerHex());
        assertNotNull(achDecoded);
        int duration = net.pilgrim.sgw.asn1.inap.InapCamelCodec.decodeApplyCharging(achDecoded);
        assertEquals(300, duration);

        client.sendAck(invite, inviteResponse, serverAddress).block(Duration.ofSeconds(2));

        // Send BYE with TC_END
        SipRequest bye = new SipRequest(SipMethod.BYE, invite.getUri());
        bye.getHeaders().setCallId(callId);
        bye.getHeaders().setFrom(inviteResponse.getFrom());
        bye.getHeaders().setTo(inviteResponse.getTo());
        bye.getHeaders().setCSeq("2 BYE");
        bye.getHeaders().setContentType("application/json");
        bye.setBody(jsonCodec.encode(TcapMessage.end(contMsg.getOtid(), List.of())));

        SipResponse byeResponse = client.send(bye, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(byeResponse);
        assertEquals(200, byeResponse.getStatusCode());
    }
}
