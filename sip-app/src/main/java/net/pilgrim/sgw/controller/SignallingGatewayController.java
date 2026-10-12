package net.pilgrim.sgw.controller;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.sgw.config.SignallingGatewayConfiguration;
import net.pilgrim.sgw.model.*;
import net.pilgrim.sgw.service.TcapDialogueManager;
import net.pilgrim.sgw.service.TcapDialogueSession;
import net.pilgrim.sgw.service.TcapSimulationHandler;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.transport.SipNettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/**
 * Reactive SIP Controller acting as a Dummy Signalling Gateway (SGW) simulating TCAP over SIP.
 * <p>
 * Supports:
 * <ul>
 *   <li>INVITE with JSON encoded {@code TC_BEGIN} payloads</li>
 *   <li>Mid-dialog INFO with JSON encoded {@code TC_CONTINUE} or {@code TC_ABORT} payloads</li>
 *   <li>BYE with JSON encoded {@code TC_END} payloads</li>
 *   <li>Server-initiated in-dialog INFO push messages</li>
 *   <li>Out-of-dialog MESSAGE with {@code TC_UNI} or single-shot TCAP transactions</li>
 *   <li>Realistic stateful dialogue tracking (OTID / DTID) and MAP / CAMEL simulation</li>
 * </ul>
 */
@Requires(property = "sgw.enabled", notEquals = "false", defaultValue = "true")
@SipController
@Singleton
public class SignallingGatewayController {

    private static final Logger LOG = LoggerFactory.getLogger(SignallingGatewayController.class);

    private final TcapDialogueManager dialogueManager;
    private final TcapSimulationHandler simulationHandler;
    private final TcapJsonCodec tcapJsonCodec;
    private final SignallingGatewayConfiguration config;
    private final SipNettyServer sipServer;

    @Inject
    public SignallingGatewayController(TcapDialogueManager dialogueManager,
                                       TcapSimulationHandler simulationHandler,
                                       TcapJsonCodec tcapJsonCodec,
                                       @Nullable SignallingGatewayConfiguration config,
                                       @Nullable SipNettyServer sipServer) {
        this.dialogueManager = dialogueManager;
        this.simulationHandler = simulationHandler;
        this.tcapJsonCodec = tcapJsonCodec != null ? tcapJsonCodec : new TcapJsonCodec();
        this.config = config != null ? config : new SignallingGatewayConfiguration();
        this.sipServer = sipServer;
    }

    // ==========================================
    // INVITE Handlers (TC_BEGIN)
    // ==========================================

    @OnInvite("sgw")
    public Mono<SipResponse> onSgwInvite(SipRequest request,
                                         @SipCallId String callId,
                                         @SipBody String body,
                                         SipSession session) {
        return handleInvite(request, callId, body, session);
    }

    @OnInvite("tcap")
    public Mono<SipResponse> onTcapInvite(SipRequest request,
                                          @SipCallId String callId,
                                          @SipBody String body,
                                          SipSession session) {
        return handleInvite(request, callId, body, session);
    }

    private Mono<SipResponse> handleInvite(SipRequest request,
                                           String callId,
                                           String body,
                                           SipSession session) {
        LOG.info("Received SGW INVITE for Call-ID: {} from: {} to: {}", callId, request.getFrom(), request.getTo());

        if (body == null || body.isBlank()) {
            LOG.warn("Rejecting SGW INVITE (missing body) for Call-ID: {}", callId);
            return Mono.just(SipResponse.badRequest(request, "Missing TCAP JSON body (expected TC_BEGIN)"));
        }

        final TcapMessage tcapMsg;
        try {
            tcapMsg = tcapJsonCodec.decode(body);
        } catch (IllegalArgumentException e) {
            LOG.warn("Failed decoding TCAP JSON from INVITE for Call-ID: {}: {}", callId, e.getMessage());
            return Mono.just(SipResponse.badRequest(request, "Malformed TCAP JSON payload: " + e.getMessage()));
        }

        if (tcapMsg.getType() != TcapMessageType.TC_BEGIN) {
            LOG.warn("Unexpected TCAP type {} in INVITE for Call-ID: {}", tcapMsg.getType(), callId);
            return Mono.just(SipResponse.badRequest(request, "Expected TC_BEGIN in INVITE payload, got: " + tcapMsg.getType()));
        }

        if (tcapMsg.getOtid() == null || tcapMsg.getOtid().isBlank()) {
            LOG.warn("Missing mandatory otid in TC_BEGIN for Call-ID: {}", callId);
            return Mono.just(SipResponse.badRequest(request, "Missing mandatory 'otid' in TC_BEGIN payload"));
        }

        String appContext = Optional.ofNullable(tcapMsg.getDialogue())
                .map(TcapDialoguePortion::getApplicationContext)
                .filter(Predicate.not(String::isBlank))
                .orElse(config.getDefaultApplicationContext());

        // Create stateful TCAP dialogue session
        TcapDialogueSession dialogueSession = dialogueManager.createSession(
                callId,
                tcapMsg.getOtid(),
                appContext,
                request.getRemoteAddress(),
                request,
                null
        );
        dialogueSession.recordMessage(tcapMsg);

        // Process components through simulation engine
        List<TcapComponent> respComponents = simulationHandler.processComponents(tcapMsg.getComponents(), dialogueSession);

        // Check if simulation requested an abort
        boolean abortRequested = tcapMsg.getComponents().stream()
                .anyMatch(c -> c.getParameters() != null && Boolean.TRUE.equals(c.getParameters().get("simulateAbort")));

        TcapMessage responseMsg;
        if (abortRequested) {
            dialogueManager.abortSession(callId, "simulatedAbort");
            responseMsg = TcapMessage.abort(dialogueSession.getServerDtid(), "simulatedAbort", "user");
        } else {
            responseMsg = TcapMessage.continueDialogue(
                    dialogueSession.getServerDtid(),
                    dialogueSession.getClientOtid(),
                    respComponents
            );
            responseMsg.setDialogue(new TcapDialoguePortion(appContext));
        }
        dialogueSession.recordMessage(responseMsg);

        String contactUri = buildContactUri(request, "sgw");
        String responseJson = tcapJsonCodec.encode(responseMsg);

        SipResponse ok = SipResponse.ok(request, responseJson, "application/json");
        ok.getHeaders().setContact(contactUri);
        ok.getHeaders().setContentType("application/json");
        ok.getHeaders().set("X-TCAP-Session-ID", dialogueSession.getDialogueId());
        ok.getHeaders().set("X-TCAP-Server-DTID", dialogueSession.getServerDtid());

        dialogueSession.setOriginalOk(ok);

        if (session != null) {
            session.handleInvite(request, null);
            session.setState(SipSession.State.EARLY);
            session.setAttribute("originalInvite", request);
            session.setAttribute("originalOk", ok);
            session.setAttribute("tcapSession", dialogueSession);
        }

        // Check if client requested server-initiated INFO push
        boolean clientPushRequested = request.getHeaders().contains("X-TCAP-Server-Push")
                || "info".equalsIgnoreCase(request.getHeaders().get("X-TCAP-Delivery"))
                || config.getDeliveryMode() == SignallingGatewayConfiguration.DeliveryMode.SERVER_INFO;

        if (clientPushRequested && !abortRequested) {
            dialogueSession.setAttribute("pendingServerPush", true);
        }

        return Mono.just(ok);
    }

    // ==========================================
    // ACK Handlers
    // ==========================================

    @OnAck("sgw")
    public void onSgwAck(SipRequest request,
                         @SipCallId String callId,
                         SipSession session) {
        handleAck(request, callId, session);
    }

    @OnAck("tcap")
    public void onTcapAck(SipRequest request,
                          @SipCallId String callId,
                          SipSession session) {
        handleAck(request, callId, session);
    }

    private void handleAck(SipRequest request, String callId, SipSession session) {
        LOG.info("Received ACK for SGW Call-ID: {}", callId);
        if (session != null) {
            session.handleAck(request, null);
        }

        dialogueManager.findByCallId(callId).ifPresent(dialogueSession -> {
            dialogueSession.touch();

            // If server-initiated INFO push was scheduled, emit it now that call is confirmed
            if (Boolean.TRUE.equals(dialogueSession.getAttribute("pendingServerPush"))) {
                dialogueSession.setAttribute("pendingServerPush", false);
                emitServerInitiatedInfo(dialogueSession);
            }
        });
    }

    // ==========================================
    // INFO Handlers (TC_CONTINUE / TC_ABORT)
    // ==========================================

    @OnInfo("sgw")
    public Mono<SipResponse> onSgwInfo(SipRequest request,
                                       @SipCallId String callId,
                                       @SipBody String body,
                                       SipSession session) {
        return handleInfo(request, callId, body, session);
    }

    @OnInfo("tcap")
    public Mono<SipResponse> onTcapInfo(SipRequest request,
                                        @SipCallId String callId,
                                        @SipBody String body,
                                        SipSession session) {
        return handleInfo(request, callId, body, session);
    }

    private Mono<SipResponse> handleInfo(SipRequest request,
                                         String callId,
                                         String body,
                                         SipSession session) {
        LOG.info("Received SGW INFO for Call-ID: {}", callId);

        Optional<TcapDialogueSession> sessionOpt = dialogueManager.findByCallId(callId);
        if (sessionOpt.isEmpty()) {
            LOG.warn("SGW INFO received for non-existent dialogue Call-ID: {}", callId);
            TcapMessage abortMsg = TcapMessage.abort(null, "unrecognizedTxId");
            SipResponse resp = SipResponse.ok(request, tcapJsonCodec.encode(abortMsg), "application/json");
            resp.getHeaders().setContentType("application/json");
            resp.getHeaders().set("X-TCAP-Error", "unrecognizedTxId");
            return Mono.just(resp);
        }

        TcapDialogueSession dialogueSession = sessionOpt.get();
        if (dialogueSession.isTerminated()) {
            LOG.warn("SGW INFO received for terminated dialogue Call-ID: {}", callId);
            TcapMessage abortMsg = TcapMessage.abort(dialogueSession.getServerDtid(), "dialogueTerminated");
            SipResponse resp = SipResponse.ok(request, tcapJsonCodec.encode(abortMsg), "application/json");
            resp.getHeaders().setContentType("application/json");
            return Mono.just(resp);
        }

        if (body == null || body.isBlank()) {
            LOG.warn("SGW INFO has empty body for Call-ID: {}", callId);
            return Mono.just(SipResponse.badRequest(request, "Missing TCAP JSON body in INFO"));
        }

        final TcapMessage incomingMsg;
        try {
            incomingMsg = tcapJsonCodec.decode(body);
        } catch (IllegalArgumentException e) {
            LOG.warn("Failed decoding TCAP JSON from INFO for Call-ID: {}: {}", callId, e.getMessage());
            return Mono.just(SipResponse.badRequest(request, "Malformed TCAP JSON payload: " + e.getMessage()));
        }

        dialogueSession.recordMessage(incomingMsg);

        // Case 1: Peer sends TC_ABORT
        if (incomingMsg.getType() == TcapMessageType.TC_ABORT) {
            LOG.info("Peer aborted dialogue Call-ID: {}, reason: {}", callId, incomingMsg.getAbortReason());
            dialogueManager.abortSession(callId, incomingMsg.getAbortReason());
            SipResponse ok = SipResponse.ok(request);
            ok.getHeaders().setContentType("application/json");
            TcapMessage ackAbort = TcapMessage.abort(dialogueSession.getServerDtid(), "peerAbortedAck", "provider");
            ok.setBody(tcapJsonCodec.encode(ackAbort));
            return Mono.just(ok);
        }

        // Case 2: Peer sends TC_END in INFO
        if (incomingMsg.getType() == TcapMessageType.TC_END) {
            LOG.info("Peer ended dialogue via INFO Call-ID: {}", callId);
            dialogueManager.terminateSession(callId);
            TcapMessage endAck = TcapMessage.end(dialogueSession.getClientOtid(), Collections.emptyList());
            SipResponse ok = SipResponse.ok(request, tcapJsonCodec.encode(endAck), "application/json");
            ok.getHeaders().setContentType("application/json");
            return Mono.just(ok);
        }

        // Case 3: Peer sends TC_CONTINUE
        if (incomingMsg.getType() != TcapMessageType.TC_CONTINUE) {
            LOG.warn("Unexpected TCAP type {} in INFO for Call-ID: {}", incomingMsg.getType(), callId);
            return Mono.just(SipResponse.badRequest(request, "Expected TC_CONTINUE in INFO payload, got: " + incomingMsg.getType()));
        }

        // Validate destination transaction ID (dtid) matches SGW serverDtid
        if (incomingMsg.getDtid() != null && !incomingMsg.getDtid().isBlank()
                && !incomingMsg.getDtid().equals(dialogueSession.getServerDtid())) {
            LOG.warn("DTID mismatch in INFO Call-ID: {}: expected {}, received {}",
                    callId, dialogueSession.getServerDtid(), incomingMsg.getDtid());
            TcapMessage abortMsg = TcapMessage.abort(incomingMsg.getDtid(), "unrecognizedTxId");
            SipResponse resp = SipResponse.ok(request, tcapJsonCodec.encode(abortMsg), "application/json");
            resp.getHeaders().setContentType("application/json");
            resp.getHeaders().set("X-TCAP-Error", "unrecognizedTxId");
            return Mono.just(resp);
        }

        // Process components
        List<TcapComponent> respComponents = simulationHandler.processComponents(incomingMsg.getComponents(), dialogueSession);

        // Check if simulation requested an abort
        boolean abortRequested = incomingMsg.getComponents().stream()
                .anyMatch(c -> c.getParameters() != null && Boolean.TRUE.equals(c.getParameters().get("simulateAbort")));

        TcapMessage responseMsg;
        if (abortRequested) {
            dialogueManager.abortSession(callId, "simulatedAbort");
            responseMsg = TcapMessage.abort(dialogueSession.getServerDtid(), "simulatedAbort", "user");
        } else {
            responseMsg = TcapMessage.continueDialogue(
                    dialogueSession.getServerDtid(),
                    dialogueSession.getClientOtid(),
                    respComponents
            );
        }
        dialogueSession.recordMessage(responseMsg);

        SipResponse ok = SipResponse.ok(request, tcapJsonCodec.encode(responseMsg), "application/json");
        ok.getHeaders().setContentType("application/json");
        ok.getHeaders().set("X-TCAP-Session-ID", dialogueSession.getDialogueId());

        return Mono.just(ok);
    }

    // ==========================================
    // BYE Handlers (TC_END)
    // ==========================================

    @OnBye("sgw")
    public Mono<SipResponse> onSgwBye(SipRequest request,
                                      @SipCallId String callId,
                                      @SipBody String body,
                                      SipSession session) {
        return handleBye(request, callId, body, session);
    }

    @OnBye("tcap")
    public Mono<SipResponse> onTcapBye(SipRequest request,
                                       @SipCallId String callId,
                                       @SipBody String body,
                                       SipSession session) {
        return handleBye(request, callId, body, session);
    }

    private Mono<SipResponse> handleBye(SipRequest request,
                                        String callId,
                                        String body,
                                        SipSession session) {
        LOG.info("Received SGW BYE for Call-ID: {}", callId);

        Optional<TcapDialogueSession> sessionOpt = dialogueManager.terminateSession(callId);
        if (session != null) {
            session.handleBye(request);
            session.setState(SipSession.State.TERMINATED);
        }

        String clientOtid = sessionOpt.map(TcapDialogueSession::getClientOtid).orElse("");
        List<TcapComponent> finalComponents = Collections.emptyList();

        if (body != null && !body.isBlank()) {
            try {
                TcapMessage endMsg = tcapJsonCodec.decode(body);
                sessionOpt.ifPresent(s -> s.recordMessage(endMsg));
                if (endMsg.getComponents() != null && !endMsg.getComponents().isEmpty() && sessionOpt.isPresent()) {
                    finalComponents = simulationHandler.processComponents(endMsg.getComponents(), sessionOpt.get());
                }
            } catch (Exception e) {
                LOG.debug("BYE body could not be parsed as TCAP JSON: {}", e.getMessage());
            }
        }

        TcapMessage endAck = TcapMessage.end(clientOtid, finalComponents);
        SipResponse ok = SipResponse.ok(request, tcapJsonCodec.encode(endAck), "application/json");
        ok.getHeaders().setContentType("application/json");
        ok.getHeaders().set("X-TCAP-Status", "TERMINATED");

        return Mono.just(ok);
    }

    // ==========================================
    // CANCEL Handler
    // ==========================================

    @OnCancel("sgw")
    public void onSgwCancel(SipRequest request,
                            @SipCallId String callId,
                            SipSession session) {
        handleCancel(request, callId, session);
    }

    @OnCancel("tcap")
    public void onTcapCancel(SipRequest request,
                             @SipCallId String callId,
                             SipSession session) {
        handleCancel(request, callId, session);
    }

    private void handleCancel(SipRequest request, String callId, SipSession session) {
        LOG.info("Received SGW CANCEL for Call-ID: {}", callId);
        dialogueManager.abortSession(callId, "callCancelled");
        if (session != null) {
            session.handleCancel(request);
        }
    }

    // ==========================================
    // OPTIONS Handler
    // ==========================================

    @OnOptions("sgw")
    public SipResponse onSgwOptions(SipRequest request) {
        return handleOptions(request);
    }

    @OnOptions("tcap")
    public SipResponse onTcapOptions(SipRequest request) {
        return handleOptions(request);
    }

    private SipResponse handleOptions(SipRequest request) {
        SipResponse response = SipResponse.ok(request);
        response.getHeaders().set(SipHeaders.ALLOW, "INVITE, ACK, BYE, CANCEL, INFO, OPTIONS, MESSAGE");
        response.getHeaders().set(SipHeaders.ACCEPT, "application/json, application/tcap+json");
        response.getHeaders().set("X-Gateway-Role", "Signalling-Gateway-TCAP-over-SIP");
        return response;
    }

    // ==========================================
    // MESSAGE Handler (TC_UNI / Out-of-Dialog)
    // ==========================================

    @OnMessage("sgw")
    public Mono<SipResponse> onSgwMessage(SipRequest request,
                                          @SipCallId String callId,
                                          @SipBody String body) {
        return handleMessage(request, callId, body);
    }

    @OnMessage("tcap")
    public Mono<SipResponse> onTcapMessage(SipRequest request,
                                           @SipCallId String callId,
                                           @SipBody String body) {
        return handleMessage(request, callId, body);
    }

    private Mono<SipResponse> handleMessage(SipRequest request, String callId, String body) {
        LOG.info("Received SGW MESSAGE for Call-ID: {}", callId);
        if (body == null || body.isBlank()) {
            return Mono.just(SipResponse.badRequest(request, "Missing TCAP payload in MESSAGE"));
        }

        try {
            TcapMessage incoming = tcapJsonCodec.decode(body);
            List<TcapComponent> results = simulationHandler.processComponents(incoming.getComponents(), null);
            TcapMessage resp = TcapMessage.uni(
                    incoming.getDialogue() != null ? incoming.getDialogue().getApplicationContext() : config.getDefaultApplicationContext(),
                    results
            );
            SipResponse ok = SipResponse.ok(request, tcapJsonCodec.encode(resp), "application/json");
            ok.getHeaders().setContentType("application/json");
            return Mono.just(ok);
        } catch (Exception e) {
            return Mono.just(SipResponse.badRequest(request, "Failed processing TCAP MESSAGE: " + e.getMessage()));
        }
    }

    // ==========================================
    // Server-Initiated INFO Push
    // ==========================================

    /**
     * Sends a server-initiated in-dialog SIP INFO message with a TCAP payload to the client.
     */
    public boolean sendServerInfo(String callId, TcapMessage message) {
        Optional<TcapDialogueSession> sessionOpt = dialogueManager.findByCallId(callId);
        if (sessionOpt.isEmpty() || sipServer == null) {
            LOG.warn("Cannot send server-initiated INFO: dialogue or SIP server unavailable for Call-ID: {}", callId);
            return false;
        }

        TcapDialogueSession session = sessionOpt.get();
        SipRequest origInvite = session.getOriginalInvite();
        SipResponse origOk = session.getOriginalOk();
        if (origInvite == null || origOk == null) {
            LOG.warn("Cannot send server-initiated INFO: missing original dialog records for Call-ID: {}", callId);
            return false;
        }

        try {
            SipUri targetUri = Optional.ofNullable(origInvite.getContact())
                    .filter(Predicate.not(String::isEmpty))
                    .map(SipUri::extractUri)
                    .or(() -> Optional.ofNullable(origInvite.getFrom()).map(SipUri::extractUri))
                    .orElseGet(origInvite::getUri);

            SipRequest info = new SipRequest(SipMethod.INFO, targetUri);
            info.setRemoteAddress(session.getRemoteAddress());
            info.setTransport(origInvite.getTransport() != null ? origInvite.getTransport() : SipTransport.UDP);

            info.getHeaders().setTo(origInvite.getFrom());
            info.getHeaders().setFrom(origOk.getTo());
            info.getHeaders().setCallId(callId);
            info.getHeaders().setCSeq(session.nextServerCSeq() + " INFO");
            info.getHeaders().setMaxForwards(70);
            info.getHeaders().setContentType("application/json");

            String jsonBody = tcapJsonCodec.encode(message);
            info.setBody(jsonBody);
            info.getHeaders().setContentLength(jsonBody.getBytes(StandardCharsets.UTF_8).length);

            String host = resolveAdvertisedIp();
            int port = Optional.ofNullable(sipServer.getTransportRegistry())
                    .map(reg -> reg.resolveServerPort(origInvite, sipServer.getConfiguration()))
                    .orElseGet(() -> (origInvite.getTransport() == SipTransport.TCP) ? sipServer.getTcpPort() : sipServer.getUdpPort());

            String proto = Optional.ofNullable(origInvite.getTransport()).map(Enum::name).orElse("UDP");
            String branch = "z9hG4bK-sgw-info-" + UUID.randomUUID().toString().substring(0, 8);
            info.getHeaders().set(SipHeaders.VIA, "SIP/2.0/" + proto + " " + host + ":" + port + ";branch=" + branch + ";rport");

            session.recordMessage(message);
            LOG.info("Sending server-initiated INFO for Call-ID: {} to {}", callId, targetUri);
            sipServer.send(info, session.getRemoteAddress());
            return true;
        } catch (Exception e) {
            LOG.warn("Failed sending server-initiated INFO for Call-ID: {}: {}", callId, e.getMessage(), e);
            return false;
        }
    }

    private void emitServerInitiatedInfo(TcapDialogueSession session) {
        TcapComponent comp = TcapComponent.returnResult(
                100,
                "serverPushNotification",
                Map.of("serverStatus", "READY", "timestamp", System.currentTimeMillis())
        );
        TcapMessage serverMsg = TcapMessage.continueDialogue(
                session.getServerDtid(),
                session.getClientOtid(),
                List.of(comp)
        );
        sendServerInfo(session.getSipCallId(), serverMsg);
    }

    private String buildContactUri(SipRequest request, String user) {
        String advertisedIp = resolveAdvertisedIp();
        int serverPort = Optional.ofNullable(sipServer)
                .map(SipNettyServer::getTransportRegistry)
                .map(reg -> reg.resolveServerPort(request, sipServer.getConfiguration()))
                .orElseGet(() -> (request.getTransport() == SipTransport.TCP)
                        ? Optional.ofNullable(sipServer).map(SipNettyServer::getTcpPort).orElse(5060)
                        : Optional.ofNullable(sipServer).map(SipNettyServer::getUdpPort).orElse(5060));

        return Optional.ofNullable(sipServer)
                .map(SipNettyServer::getTransportRegistry)
                .map(reg -> reg.formatContactUri(request, advertisedIp, serverPort, user))
                .orElseGet(() -> {
                    boolean isTls = request.getTransport() == SipTransport.TLS;
                    String scheme = isTls ? "sips" : "sip";
                    String transportParam = Optional.ofNullable(request.getTransport())
                            .filter(t -> t != SipTransport.UDP)
                            .map(t -> ";transport=" + t.name().toLowerCase(Locale.ROOT))
                            .orElse("");
                    return "<" + scheme + ":" + user + "@" + advertisedIp + ":" + serverPort + transportParam + ">";
                });
    }

    private String resolveAdvertisedIp() {
        return Optional.ofNullable(config.getAdvertisedIp())
                .filter(Predicate.not(String::isBlank))
                .or(() -> Optional.ofNullable(sipServer)
                        .map(SipNettyServer::getConfiguration)
                        .map(SipServerConfiguration::resolveAdvertisedIp))
                .orElse("127.0.0.1");
    }

    public TcapDialogueManager getDialogueManager() {
        return dialogueManager;
    }

    public TcapSimulationHandler getSimulationHandler() {
        return simulationHandler;
    }
}
