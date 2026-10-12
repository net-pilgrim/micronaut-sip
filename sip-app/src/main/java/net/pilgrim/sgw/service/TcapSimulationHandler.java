package net.pilgrim.sgw.service;

import jakarta.inject.Singleton;
import net.pilgrim.sgw.model.TcapComponent;
import net.pilgrim.sgw.model.TcapComponentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Realistic telecom signalling simulation engine for TCAP components (MAP / CAMEL / Telephony operations).
 * Tracks operations, simulates responses/errors/aborts, and supports configurable custom mock handlers.
 */
@Singleton
public class TcapSimulationHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TcapSimulationHandler.class);

    private final Map<String, BiFunction<TcapComponent, TcapDialogueSession, TcapComponent>> customHandlers = new ConcurrentHashMap<>();

    public TcapSimulationHandler() {
        registerBuiltInHandlers();
    }

    /**
     * Registers a custom mock operation handler.
     */
    public void registerHandler(String operation, BiFunction<TcapComponent, TcapDialogueSession, TcapComponent> handler) {
        if (operation != null && handler != null) {
            customHandlers.put(operation.toLowerCase(Locale.ROOT), handler);
        }
    }

    /**
     * Removes a custom mock handler.
     */
    public void unregisterHandler(String operation) {
        if (operation != null) {
            customHandlers.remove(operation.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Evaluates a list of incoming TCAP components against an active dialogue session.
     * Returns the simulated response components (e.g. RETURN_RESULT_LAST, RETURN_ERROR, REJECT).
     */
    public List<TcapComponent> processComponents(List<TcapComponent> incoming, TcapDialogueSession session) {
        if (incoming == null || incoming.isEmpty()) {
            return Collections.emptyList();
        }

        List<TcapComponent> responses = new ArrayList<>();
        for (TcapComponent comp : incoming) {
            TcapComponent resp = processComponent(comp, session);
            if (resp != null) {
                responses.add(resp);
            }
        }
        return responses;
    }

    /**
     * Processes a single component (typically an INVOKE component).
     */
    public TcapComponent processComponent(TcapComponent comp, TcapDialogueSession session) {
        if (comp == null) {
            return null;
        }

        int invokeId = comp.getInvokeId() != null ? comp.getInvokeId() : 1;

        // If client sent a RETURN_RESULT or RETURN_ERROR, server acknowledges receipt
        if (comp.getType() == TcapComponentType.RETURN_RESULT_LAST
                || comp.getType() == TcapComponentType.RETURN_RESULT_NOT_LAST
                || comp.getType() == TcapComponentType.RETURN_ERROR
                || comp.getType() == TcapComponentType.REJECT) {
            LOG.info("Received client component response: type={}, invokeId={}", comp.getType(), invokeId);
            return null;
        }

        String op = comp.getOperation() != null ? comp.getOperation().trim() : "echo";
        String normalizedOp = op.toLowerCase(Locale.ROOT);

        Map<String, Object> params = comp.getParameters() != null ? comp.getParameters() : Collections.emptyMap();

        // 1. Check for explicit error simulation parameter: "simulateError"
        Object simErrorObj = params.get("simulateError");
        if (simErrorObj == null) {
            simErrorObj = params.get("simulate_error");
        }
        if (simErrorObj != null) {
            String errorCode = simErrorObj.toString();
            LOG.info("Simulating RETURN_ERROR '{}' for invokeId={}, op={}", errorCode, invokeId, op);
            return TcapComponent.returnError(invokeId, errorCode, Map.of(
                    "simulatedError", true,
                    "cause", "Requested via simulateError parameter"
            ));
        }

        // 2. Check for explicit reject simulation parameter: "simulateReject"
        Object simRejectObj = params.get("simulateReject");
        if (simRejectObj != null) {
            String problem = simRejectObj.toString();
            LOG.info("Simulating REJECT '{}' for invokeId={}, op={}", problem, invokeId, op);
            return TcapComponent.reject(invokeId, problem);
        }

        // 3. Dispatch to custom or registered handler
        BiFunction<TcapComponent, TcapDialogueSession, TcapComponent> handler = customHandlers.get(normalizedOp);
        if (handler != null) {
            try {
                return handler.apply(comp, session);
            } catch (Exception e) {
                LOG.error("Error executing handler for operation '{}': {}", op, e.getMessage(), e);
                return TcapComponent.returnError(invokeId, "systemFailure", Map.of("error", e.getMessage()));
            }
        }

        // 4. Default fallback: generic echo result
        LOG.info("Executing default echo handler for unmapped operation '{}', invokeId={}", op, invokeId);
        Map<String, Object> echoResult = new LinkedHashMap<>(params);
        echoResult.put("simulatedResult", true);
        echoResult.put("operation", op);
        echoResult.put("status", "SUCCESS");
        return TcapComponent.returnResult(invokeId, op, echoResult);
    }

    private void registerBuiltInHandlers() {
        // GSM MAP SRI-for-SM (sendRoutingInfoForSM)
        registerHandler("sendroutinginfoforsm", this::handleSendRoutingInfoForSM);
        registerHandler("sriforsm", this::handleSendRoutingInfoForSM);

        // GSM MAP PSI (provideSubscriberInfo)
        registerHandler("providesubscriberinfo", this::handleProvideSubscriberInfo);
        registerHandler("psi", this::handleProvideSubscriberInfo);

        // GSM MAP Check IMEI
        registerHandler("checkimei", this::handleCheckIMEI);

        // GSM MAP MO/MT Forward SM
        registerHandler("mo-forwardsm", this::handleForwardSM);
        registerHandler("moforwardsm", this::handleForwardSM);
        registerHandler("mt-forwardsm", this::handleForwardSM);
        registerHandler("mtforwardsm", this::handleForwardSM);

        // GSM MAP ATI (anyTimeInterrogation)
        registerHandler("anytimeinterrogation", this::handleAnyTimeInterrogation);
        registerHandler("ati", this::handleAnyTimeInterrogation);

        // INAP / CAMEL Operations (ITU-T Q.1218 / 3GPP TS 29.078)
        registerHandler("initialdp", this::handleInitialDP);
        registerHandler("initial-dp", this::handleInitialDP);
        registerHandler("idp", this::handleInitialDP);
        registerHandler("connect", this::handleConnect);
        registerHandler("applycharging", this::handleApplyCharging);
        registerHandler("releasecall", this::handleReleaseCall);
        registerHandler("playannouncement", this::handlePlayAnnouncement);
        registerHandler("play-announcement", this::handlePlayAnnouncement);
        registerHandler("playandcollectuserinfo", this::handlePromptAndCollectUserInfo);
        registerHandler("play-and-collect-user-info", this::handlePromptAndCollectUserInfo);
        registerHandler("promptandcollectuserinformation", this::handlePromptAndCollectUserInfo);
        registerHandler("prompt-and-collect-user-information", this::handlePromptAndCollectUserInfo);
        registerHandler("pacui", this::handlePromptAndCollectUserInfo);
        registerHandler("continue", (comp, sess) -> {
            int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
            TcapComponent res = TcapComponent.returnResult(id, "continue", Map.of("status", "CONTINUE"));
            res.setBerHex(net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodeContinue().toHex());
            return res;
        });

        // Echo operation
        registerHandler("echo", (comp, sess) -> {
            int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("echo", true);
            if (comp.getParameters() != null) {
                res.putAll(comp.getParameters());
            }
            return TcapComponent.returnResult(id, "echo", res);
        });
    }

    private TcapComponent handleSendRoutingInfoForSM(TcapComponent comp, TcapDialogueSession session) {
        int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
        Map<String, Object> p = comp.getParameters() != null ? comp.getParameters() : Collections.emptyMap();
        String msisdn = Objects.toString(p.get("msisdn"), "33612345678");

        if (msisdn.endsWith("000")) {
            return TcapComponent.returnError(id, "unknownSubscriber", Map.of("msisdn", msisdn));
        }
        if (msisdn.endsWith("999")) {
            return TcapComponent.returnError(id, "absentSubscriberSM", Map.of("msisdn", msisdn));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imsi", "20801" + String.format("%010d", Math.abs(msisdn.hashCode() % 1_000_000_0000L)));
        result.put("mscNumber", "+33612340001");
        result.put("sgsnNumber", "+33612340002");
        result.put("msisdn", msisdn);
        return TcapComponent.returnResult(id, "sendRoutingInfoForSM", result);
    }

    private TcapComponent handleProvideSubscriberInfo(TcapComponent comp, TcapDialogueSession session) {
        int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
        Map<String, Object> p = comp.getParameters() != null ? comp.getParameters() : Collections.emptyMap();
        String imsi = Objects.toString(p.get("imsi"), "208010123456789");

        if (imsi.endsWith("000")) {
            return TcapComponent.returnError(id, "unknownSubscriber", Map.of("imsi", imsi));
        }

        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("cellGlobalId", "208-01-1234-5678");
        loc.put("locationAreaCode", 1234);
        loc.put("cellId", 5678);
        loc.put("vlrNumber", "+33612340001");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imsi", imsi);
        result.put("locationInformation", loc);
        result.put("subscriberState", "idle");
        result.put("ageOfLocationInformation", 0);
        return TcapComponent.returnResult(id, "provideSubscriberInfo", result);
    }

    private TcapComponent handleCheckIMEI(TcapComponent comp, TcapDialogueSession session) {
        int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
        Map<String, Object> p = comp.getParameters() != null ? comp.getParameters() : Collections.emptyMap();
        String imei = Objects.toString(p.get("imei"), "354000000000000");

        String status = "whiteListed";
        if (imei.startsWith("0000") || imei.endsWith("999")) {
            status = "blackListed";
        } else if (imei.startsWith("1111")) {
            status = "greyListed";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imei", imei);
        result.put("equipmentStatus", status);
        return TcapComponent.returnResult(id, "checkIMEI", result);
    }

    private TcapComponent handleForwardSM(TcapComponent comp, TcapDialogueSession session) {
        int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
        Map<String, Object> p = comp.getParameters() != null ? comp.getParameters() : Collections.emptyMap();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deliveryOutcome", "successfulTransfer");
        result.put("messageReference", "ref-" + UUID.randomUUID().toString().substring(0, 8));
        result.put("timestamp", System.currentTimeMillis());
        return TcapComponent.returnResult(id, comp.getOperation(), result);
    }

    private TcapComponent handleAnyTimeInterrogation(TcapComponent comp, TcapDialogueSession session) {
        int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
        Map<String, Object> p = comp.getParameters() != null ? comp.getParameters() : Collections.emptyMap();
        String msisdn = Objects.toString(p.get("msisdn"), "33612345678");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("msisdn", msisdn);
        result.put("subscriberState", "netOutOfCoverage");
        result.put("locationInformation", Map.of("cellId", "1234", "lac", "567"));
        return TcapComponent.returnResult(id, "anyTimeInterrogation", result);
    }

    private TcapComponent handleInitialDP(TcapComponent comp, TcapDialogueSession session) {
        int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
        Map<String, Object> p = comp.getParameters() != null ? comp.getParameters() : Collections.emptyMap();

        int serviceKey = 1;
        if (p.get("serviceKey") instanceof Number n) {
            serviceKey = n.intValue();
        }
        String calledParty = Objects.toString(p.get("calledPartyNumber"), "33612345678");
        String callingParty = Objects.toString(p.get("callingPartyNumber"), "33698765432");

        // Error simulation
        if (calledParty.endsWith("000")) {
            return TcapComponent.returnError(id, "missingCustomerRecord", Map.of(
                    "serviceKey", serviceKey,
                    "calledPartyNumber", calledParty
            ));
        }

        // Call redirection / translation (e.g. Toll-free or Premium rate numbers)
        if (calledParty.startsWith("800") || calledParty.startsWith("900")) {
            String translatedDest = "3314000" + calledParty.substring(Math.max(0, calledParty.length() - 4));
            net.pilgrim.sgw.asn1.BerTlv connectBer = net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodeConnect(translatedDest);

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("operation", "connect");
            res.put("destinationRoutingAddress", translatedDest);
            res.put("originalCalledParty", calledParty);

            TcapComponent connectComp = TcapComponent.returnResult(id, "connect", res);
            connectComp.setBerHex(connectBer.toHex());
            return connectComp;
        }

        // IVR: Play Announcement simulation (e.g. prefix 701, 777, or explicit parameter)
        if (calledParty.startsWith("701") || calledParty.startsWith("777") || "playAnnouncement".equalsIgnoreCase(Objects.toString(p.get("action"), ""))) {
            int msgId = 101;
            String announcement = "Welcome to the Signalling Gateway voice announcement service.";
            net.pilgrim.sgw.asn1.BerTlv paBer = net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodePlayAnnouncement(
                    msgId, announcement, 1, 10, true, true);

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("operation", "playAnnouncement");
            res.put("messageId", msgId);
            res.put("announcementText", announcement);
            res.put("numberOfLoops", 1);
            res.put("duration", 10);
            res.put("requestAnnouncementComplete", true);

            TcapComponent paComp = TcapComponent.invoke(id, "playAnnouncement", res);
            paComp.setBerHex(paBer.toHex());
            return paComp;
        }

        // IVR: Prompt and Collect User Information simulation (e.g. prefix 702, ends with 888, or explicit parameter)
        if (calledParty.startsWith("702") || calledParty.endsWith("888") || "playAndCollectUserInfo".equalsIgnoreCase(Objects.toString(p.get("action"), ""))) {
            int msgId = 201;
            String prompt = "Please enter your 4-digit PIN followed by the pound key.";
            net.pilgrim.sgw.asn1.BerTlv pacBer = net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodePromptAndCollectUserInfo(
                    msgId, prompt, 1, 4, "#", "*", 10, 5, true, true);

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("operation", "promptAndCollectUserInformation");
            res.put("messageId", msgId);
            res.put("announcementText", prompt);
            res.put("minDigits", 1);
            res.put("maxDigits", 4);
            res.put("endOfReplyDigit", "#");
            res.put("cancelDigit", "*");

            TcapComponent pacComp = TcapComponent.invoke(id, "promptAndCollectUserInformation", res);
            pacComp.setBerHex(pacBer.toHex());
            return pacComp;
        }

        // Default INAP/CAMEL response: ApplyCharging (allow call for N seconds)
        int maxDuration = 300;
        net.pilgrim.sgw.asn1.BerTlv achBer = net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodeApplyCharging(maxDuration);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("operation", "applyCharging");
        res.put("maxCallPeriodDuration", maxDuration);
        res.put("partyToCharge", "callingParty");
        res.put("serviceKey", serviceKey);
        res.put("calledPartyNumber", calledParty);
        res.put("callingPartyNumber", callingParty);

        TcapComponent achComp = TcapComponent.returnResult(id, "applyCharging", res);
        achComp.setBerHex(achBer.toHex());
        return achComp;
    }

    private TcapComponent handleConnect(TcapComponent comp, TcapDialogueSession session) {
        int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
        Map<String, Object> p = comp.getParameters() != null ? comp.getParameters() : Collections.emptyMap();
        String dest = Objects.toString(p.get("destinationRoutingAddress"), "33612349999");
        net.pilgrim.sgw.asn1.BerTlv ber = net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodeConnect(dest);

        Map<String, Object> res = new LinkedHashMap<>(p);
        res.put("destinationRoutingAddress", dest);
        res.put("status", "CONNECTED");
        TcapComponent result = TcapComponent.returnResult(id, "connect", res);
        result.setBerHex(ber.toHex());
        return result;
    }

    private TcapComponent handleApplyCharging(TcapComponent comp, TcapDialogueSession session) {
        int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
        Map<String, Object> p = comp.getParameters() != null ? comp.getParameters() : Collections.emptyMap();
        int maxDuration = 300;
        if (p.get("maxCallPeriodDuration") instanceof Number n) {
            maxDuration = n.intValue();
        }
        net.pilgrim.sgw.asn1.BerTlv ber = net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodeApplyCharging(maxDuration);

        Map<String, Object> res = new LinkedHashMap<>(p);
        res.put("maxCallPeriodDuration", maxDuration);
        res.put("chargingGranted", true);
        TcapComponent result = TcapComponent.returnResult(id, "applyCharging", res);
        result.setBerHex(ber.toHex());
        return result;
    }

    private TcapComponent handleReleaseCall(TcapComponent comp, TcapDialogueSession session) {
        int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
        Map<String, Object> p = comp.getParameters() != null ? comp.getParameters() : Collections.emptyMap();
        int cause = 16;
        if (p.get("cause") instanceof Number n) {
            cause = n.intValue();
        }
        net.pilgrim.sgw.asn1.BerTlv ber = net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodeReleaseCall(cause);

        Map<String, Object> res = new LinkedHashMap<>(p);
        res.put("cause", cause);
        res.put("causeDescription", "Normal clearing (Q.850 16)");
        TcapComponent result = TcapComponent.returnResult(id, "releaseCall", res);
        result.setBerHex(ber.toHex());
        return result;
    }

    private TcapComponent handlePlayAnnouncement(TcapComponent comp, TcapDialogueSession session) {
        int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
        Map<String, Object> p = comp.getParameters() != null ? comp.getParameters() : Collections.emptyMap();

        int messageId = 101;
        if (p.get("messageId") instanceof Number n) {
            messageId = n.intValue();
        }
        String text = (String) p.get("announcementText");
        int duration = 10;
        if (p.get("duration") instanceof Number n) {
            duration = n.intValue();
        }

        net.pilgrim.sgw.asn1.BerTlv ber = net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodeSpecializedResourceReport();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("operation", "specializedResourceReport");
        res.put("status", "ANNOUNCEMENT_COMPLETED");
        res.put("messageId", messageId);
        if (text != null) res.put("announcementText", text);
        res.put("durationPlayedSeconds", duration);

        TcapComponent result = TcapComponent.returnResult(id, "specializedResourceReport", res);
        result.setBerHex(ber.toHex());
        return result;
    }

    private TcapComponent handlePromptAndCollectUserInfo(TcapComponent comp, TcapDialogueSession session) {
        int id = comp.getInvokeId() != null ? comp.getInvokeId() : 1;
        Map<String, Object> p = comp.getParameters() != null ? comp.getParameters() : Collections.emptyMap();

        int messageId = 201;
        if (p.get("messageId") instanceof Number n) {
            messageId = n.intValue();
        }
        String endDigit = Objects.toString(p.get("endOfReplyDigit"), "#");

        // Allow caller to inject simulated digits or default to "1234#"
        String collectedDigits = Objects.toString(p.get("simulatedDigits"), "1234" + endDigit);

        net.pilgrim.sgw.asn1.BerTlv ber = net.pilgrim.sgw.asn1.inap.InapCamelCodec.encodeDigitsResponse(collectedDigits);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("operation", "promptAndCollectUserInformation");
        res.put("digitsResponse", collectedDigits);
        res.put("messageId", messageId);

        TcapComponent result = TcapComponent.returnResult(id, "promptAndCollectUserInformation", res);
        result.setBerHex(ber.toHex());
        return result;
    }
}
