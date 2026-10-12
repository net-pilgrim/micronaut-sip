package net.pilgrim.sgw.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * Resilient JSON encoder/decoder for TCAP messages over SIP.
 * Normalizes mixed-case field names and enum values (e.g., 'TC_begin', 'tc_continue', 'tc_end').
 */
@Singleton
public class TcapJsonCodec {

    private static final Logger LOG = LoggerFactory.getLogger(TcapJsonCodec.class);

    private final JsonMapper jsonMapper;

    public TcapJsonCodec() {
        this(null);
    }

    @Inject
    public TcapJsonCodec(@Nullable JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper != null ? jsonMapper : JsonMapper.createDefault();
    }

    /**
     * Serializes a TcapMessage into a JSON string.
     */
    public String encode(TcapMessage message) {
        if (message == null) {
            return "{}";
        }
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            if (message.getType() != null) {
                map.put("type", message.getType().name());
            }
            if (message.getOtid() != null) {
                map.put("otid", message.getOtid());
            }
            if (message.getDtid() != null) {
                map.put("dtid", message.getDtid());
            }
            if (message.getDialogue() != null) {
                Map<String, Object> dlgMap = new LinkedHashMap<>();
                if (message.getDialogue().getApplicationContext() != null) {
                    dlgMap.put("applicationContext", message.getDialogue().getApplicationContext());
                }
                if (message.getDialogue().getProtocolVersion() != null) {
                    dlgMap.put("protocolVersion", message.getDialogue().getProtocolVersion());
                }
                if (message.getDialogue().getSecurityContext() != null) {
                    dlgMap.put("securityContext", message.getDialogue().getSecurityContext());
                }
                if (message.getDialogue().getUserInfo() != null && !message.getDialogue().getUserInfo().isEmpty()) {
                    dlgMap.put("userInfo", message.getDialogue().getUserInfo());
                }
                map.put("dialogue", dlgMap);
            }
            if (message.getComponents() != null && !message.getComponents().isEmpty()) {
                List<Map<String, Object>> compList = new ArrayList<>();
                for (TcapComponent c : message.getComponents()) {
                    Map<String, Object> compMap = new LinkedHashMap<>();
                    if (c.getType() != null) {
                        compMap.put("type", c.getType().name());
                    }
                    if (c.getInvokeId() != null) {
                        compMap.put("invokeId", c.getInvokeId());
                    }
                    if (c.getLinkedId() != null) {
                        compMap.put("linkedId", c.getLinkedId());
                    }
                    if (c.getOperation() != null) {
                        compMap.put("operation", c.getOperation());
                    }
                    if (c.getParameters() != null && !c.getParameters().isEmpty()) {
                        compMap.put("parameters", c.getParameters());
                    }
                    if (c.getErrorCode() != null) {
                        compMap.put("errorCode", c.getErrorCode());
                    }
                    if (c.getErrorParameters() != null && !c.getErrorParameters().isEmpty()) {
                        compMap.put("errorParameters", c.getErrorParameters());
                    }
                    if (c.getProblem() != null) {
                        compMap.put("problem", c.getProblem());
                    }
                    if (c.getBerHex() != null && !c.getBerHex().isBlank()) {
                        compMap.put("berHex", c.getBerHex());
                    }
                    if (c.getBerBase64() != null && !c.getBerBase64().isBlank()) {
                        compMap.put("berBase64", c.getBerBase64());
                    }
                    compList.add(compMap);
                }
                map.put("components", compList);
            }
            if (message.getAbortReason() != null) {
                map.put("abortReason", message.getAbortReason());
            }
            if (message.getAbortSource() != null) {
                map.put("abortSource", message.getAbortSource());
            }
            return jsonMapper.writeValueAsString(map);
        } catch (IOException e) {
            LOG.error("Failed encoding TcapMessage to JSON: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to serialize TcapMessage to JSON", e);
        }
    }

    /**
     * Decodes a JSON string into a TcapMessage, handling case-insensitive attributes and aliases.
     */
    @SuppressWarnings("unchecked")
    public TcapMessage decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("TCAP JSON payload is empty or blank");
        }
        try {
            Map<String, Object> map = jsonMapper.readValue(json.trim(), Map.class);
            if (map == null || map.isEmpty()) {
                throw new IllegalArgumentException("Invalid empty TCAP JSON object");
            }

            String typeStr = findString(map, "type", "messageType", "message_type");
            if (typeStr == null) {
                throw new IllegalArgumentException("Missing mandatory 'type' in TCAP JSON payload");
            }
            TcapMessageType type = TcapMessageType.fromString(typeStr);

            TcapMessage msg = new TcapMessage(type);
            msg.setOtid(findString(map, "otid", "origTxId", "originatingTransactionId"));
            msg.setDtid(findString(map, "dtid", "destTxId", "destinationTransactionId"));
            msg.setAbortReason(findString(map, "abortReason", "reason", "pAbortCause", "uAbortCause", "abort_reason"));
            msg.setAbortSource(findString(map, "abortSource", "source", "abort_source"));

            Object dlgObj = map.get("dialogue");
            if (dlgObj == null) {
                dlgObj = map.get("dialoguePortion");
            }
            if (dlgObj instanceof Map<?, ?> dlgMapRaw) {
                Map<String, Object> dlgMap = (Map<String, Object>) dlgMapRaw;
                TcapDialoguePortion dlg = new TcapDialoguePortion();
                dlg.setApplicationContext(findString(dlgMap, "applicationContext", "appContext", "application_context"));
                dlg.setProtocolVersion(findInteger(dlgMap, "protocolVersion", "version", "protocol_version"));
                dlg.setSecurityContext(findString(dlgMap, "securityContext", "security_context"));
                Object userInfo = dlgMap.get("userInfo");
                if (userInfo instanceof Map<?, ?> uiMap) {
                    dlg.setUserInfo((Map<String, Object>) uiMap);
                }
                msg.setDialogue(dlg);
            }

            Object compsObj = map.get("components");
            if (compsObj == null) {
                compsObj = map.get("component");
            }
            if (compsObj instanceof List<?> compListRaw) {
                for (Object item : compListRaw) {
                    if (item instanceof Map<?, ?> compMapRaw) {
                        Map<String, Object> compMap = (Map<String, Object>) compMapRaw;
                        String compTypeStr = findString(compMap, "type", "componentType", "component_type");
                        TcapComponentType compType = (compTypeStr != null) ? TcapComponentType.fromString(compTypeStr) : TcapComponentType.INVOKE;
                        Integer invokeId = findInteger(compMap, "invokeId", "id", "invoke_id");
                        Integer linkedId = findInteger(compMap, "linkedId", "linked_id");
                        String operation = findString(compMap, "operation", "op", "operationName", "operationCode");

                        Map<String, Object> params = null;
                        Object pObj = compMap.get("parameters");
                        if (pObj == null) pObj = compMap.get("params");
                        if (pObj == null) pObj = compMap.get("result");
                        if (pObj == null) pObj = compMap.get("data");
                        if (pObj instanceof Map<?, ?> pMap) {
                            params = (Map<String, Object>) pMap;
                        }

                        TcapComponent comp = new TcapComponent(compType, invokeId, operation, params);
                        comp.setLinkedId(linkedId);
                        comp.setErrorCode(findString(compMap, "errorCode", "error", "error_code"));
                        comp.setProblem(findString(compMap, "problem", "problemCode", "problem_code"));

                        Object errPObj = compMap.get("errorParameters");
                        if (errPObj instanceof Map<?, ?> errMap) {
                            comp.setErrorParameters((Map<String, Object>) errMap);
                        }

                        String berHex = findString(compMap, "berHex", "ber_hex", "asn1Hex", "asn1_hex", "asn1", "payloadHex");
                        String berBase64 = findString(compMap, "berBase64", "ber_base64", "asn1Base64", "asn1_base64", "payloadBase64");
                        comp.setBerHex(berHex);
                        comp.setBerBase64(berBase64);

                        if (berHex != null && !berHex.isBlank()) {
                            try {
                                net.pilgrim.sgw.asn1.BerTlv tlv = net.pilgrim.sgw.asn1.BerCodec.decodeHex(berHex);
                                if (params == null) {
                                    params = new LinkedHashMap<>();
                                    comp.setParameters(params);
                                }
                                params.putIfAbsent("asn1Tlv", tlv.toMap());
                                if ("initialDP".equalsIgnoreCase(operation) || "initial-dp".equalsIgnoreCase(operation) || "idp".equalsIgnoreCase(operation)) {
                                    var idp = net.pilgrim.sgw.asn1.inap.InapCamelCodec.decodeInitialDP(tlv);
                                    params.putIfAbsent("serviceKey", idp.serviceKey());
                                    if (idp.calledPartyNumber() != null && !idp.calledPartyNumber().isBlank()) {
                                        params.putIfAbsent("calledPartyNumber", idp.calledPartyNumber());
                                    }
                                    if (idp.callingPartyNumber() != null && !idp.callingPartyNumber().isBlank()) {
                                        params.putIfAbsent("callingPartyNumber", idp.callingPartyNumber());
                                    }
                                } else if ("connect".equalsIgnoreCase(operation)) {
                                    String dest = net.pilgrim.sgw.asn1.inap.InapCamelCodec.decodeConnect(tlv);
                                    if (dest != null) params.putIfAbsent("destinationRoutingAddress", dest);
                                } else if ("applyCharging".equalsIgnoreCase(operation)) {
                                    int dur = net.pilgrim.sgw.asn1.inap.InapCamelCodec.decodeApplyCharging(tlv);
                                    params.putIfAbsent("maxCallPeriodDuration", dur);
                                } else if ("playAnnouncement".equalsIgnoreCase(operation) || "play-announcement".equalsIgnoreCase(operation)) {
                                    var pa = net.pilgrim.sgw.asn1.inap.InapCamelCodec.decodePlayAnnouncement(tlv);
                                    if (pa.messageId() > 0) params.putIfAbsent("messageId", pa.messageId());
                                    if (pa.announcementText() != null) params.putIfAbsent("announcementText", pa.announcementText());
                                    params.putIfAbsent("numberOfLoops", pa.numberOfLoops());
                                    if (pa.duration() > 0) params.putIfAbsent("duration", pa.duration());
                                    params.putIfAbsent("disconnectFromIpForbidden", pa.disconnectFromIpForbidden());
                                    params.putIfAbsent("requestAnnouncementComplete", pa.requestAnnouncementComplete());
                                } else if ("playAndCollectUserInfo".equalsIgnoreCase(operation) || "play-and-collect-user-info".equalsIgnoreCase(operation) ||
                                           "promptAndCollectUserInformation".equalsIgnoreCase(operation) || "prompt-and-collect-user-information".equalsIgnoreCase(operation) ||
                                           "pacui".equalsIgnoreCase(operation)) {
                                    var pac = net.pilgrim.sgw.asn1.inap.InapCamelCodec.decodePromptAndCollectUserInfo(tlv);
                                    if (pac.messageId() > 0) params.putIfAbsent("messageId", pac.messageId());
                                    if (pac.announcementText() != null) params.putIfAbsent("announcementText", pac.announcementText());
                                    params.putIfAbsent("minDigits", pac.minDigits());
                                    params.putIfAbsent("maxDigits", pac.maxDigits());
                                    if (pac.endOfReplyDigit() != null) params.putIfAbsent("endOfReplyDigit", pac.endOfReplyDigit());
                                    if (pac.cancelDigit() != null) params.putIfAbsent("cancelDigit", pac.cancelDigit());
                                    if (pac.firstDigitTimeout() > 0) params.putIfAbsent("firstDigitTimeout", pac.firstDigitTimeout());
                                    if (pac.interDigitTimeout() > 0) params.putIfAbsent("interDigitTimeout", pac.interDigitTimeout());
                                    params.putIfAbsent("interruptable", pac.interruptable());
                                    params.putIfAbsent("disconnectFromIpForbidden", pac.disconnectFromIpForbidden());
                                } else if ("digitsResponse".equalsIgnoreCase(operation) || "receivedInformation".equalsIgnoreCase(operation)) {
                                    String digits = net.pilgrim.sgw.asn1.inap.InapCamelCodec.decodeDigitsResponse(tlv);
                                    if (digits != null && !digits.isEmpty()) params.putIfAbsent("digitsResponse", digits);
                                }
                                comp.setParameters(params);
                            } catch (Exception e) {
                                LOG.debug("Could not auto-parse BER hex for operation {}: {}", operation, e.getMessage());
                            }
                        }

                        msg.addComponent(comp);
                    }
                }
            }

            return msg;
        } catch (IOException e) {
            LOG.warn("Failed parsing TCAP JSON: {}", e.getMessage());
            throw new IllegalArgumentException("Malformed JSON payload: " + e.getMessage(), e);
        }
    }

    private static String findString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return Objects.toString(entry.getValue(), null);
                }
            }
        }
        return null;
    }

    private static Integer findInteger(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    Object val = entry.getValue();
                    if (val instanceof Number n) {
                        return n.intValue();
                    }
                    if (val instanceof String s && !s.isBlank()) {
                        try {
                            return Integer.parseInt(s.trim());
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return null;
    }
}
