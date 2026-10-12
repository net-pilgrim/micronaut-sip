package net.pilgrim.msml.server;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.msml.builder.MsmlBuilder;
import net.pilgrim.msml.builder.MsmlSerializer;
import net.pilgrim.msml.engine.MsmlMediaEngine;
import net.pilgrim.msml.model.MsmlCommand;
import net.pilgrim.msml.model.MsmlDocument;
import net.pilgrim.msml.model.MsmlTarget;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;
import net.pilgrim.msml.parser.MsmlParseException;
import net.pilgrim.msml.parser.MsmlParser;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.rtp.media.RtpMediaManager;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.sip.sdp.SdpNegotiator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SIP controller for RFC 5707 Media Server Markup Language (MSML) server endpoints.
 * <p>
 * Handles mid-dialog MSML commands via SIP INFO requests, establishes media sessions
 * via SIP INVITE, and dispatches execution to {@link MsmlMediaEngine}.
 */
@SipController
@Singleton
public class MsmlServerController {

    private static final Logger LOG = LoggerFactory.getLogger(MsmlServerController.class);

    private final MsmlMediaEngine mediaEngine;
    private final MsmlParser parser;
    private final RtpMediaManager rtpMediaManager;
    private final SdpNegotiator sdpNegotiator;
    private final ReactiveSipClient sipClient;

    // Call-ID to remote contact/address mapping for sending in-dialog event notifications
    private final Map<String, InetSocketAddress> callRemoteAddresses = new ConcurrentHashMap<>();

    @Inject
    public MsmlServerController(MsmlMediaEngine mediaEngine,
                                @Nullable MsmlParser parser,
                                RtpMediaManager rtpMediaManager,
                                @Nullable SdpNegotiator sdpNegotiator,
                                @Nullable ReactiveSipClient sipClient) {
        this.mediaEngine = mediaEngine;
        this.parser = parser != null ? parser : new MsmlParser();
        this.rtpMediaManager = rtpMediaManager;
        this.sdpNegotiator = sdpNegotiator != null ? sdpNegotiator : new SdpNegotiator();
        this.sipClient = sipClient;

        // Register event listener to forward async MSML events to the Application Server
        this.mediaEngine.addEventListener(this::handleMediaEvent);
    }

    /**
     * Handles mid-dialog MSML commands sent via SIP INFO (RFC 2976 / RFC 6086 / RFC 5707 Section 2).
     */
    @OnInfo
    public Mono<SipResponse> onInfo(SipRequest request,
                                    @SipCallId String callId,
                                    @Nullable @SipBody String body,
                                    @Nullable @SipHeader("Content-Type") String contentType) {
        LOG.debug("Received SIP INFO on Call-ID: {} with Content-Type: {}", callId, contentType);

        if (body == null || body.isBlank() || (!isMsmlPayload(contentType, body))) {
            return Mono.just(request.createResponse(415, "Unsupported Media Type"));
        }

        if (request.getRemoteAddress() != null) {
            callRemoteAddresses.put(callId, request.getRemoteAddress());
        }

        try {
            MsmlDocument doc = parser.parse(body);

            if (doc.isCommand()) {
                List<MsmlResult> results = new ArrayList<>();
                for (MsmlCommand cmd : doc.commands()) {
                    results.add(executeCommand(cmd, callId));
                }

                MsmlDocument resultDoc = MsmlDocument.ofResults(results);
                String resultXml = MsmlSerializer.serialize(resultDoc);

                SipResponse response = request.createResponse(200, "OK");
                response.getHeaders().setContentType(MsmlDocument.CONTENT_TYPE);
                response.setBody(resultXml);
                return Mono.just(response);
            }

            // Results or events received from client: acknowledge with 200 OK
            return Mono.just(request.createResponse(200, "OK"));

        } catch (MsmlParseException e) {
            LOG.warn("Failed parsing MSML in INFO for Call-ID {}: {}", callId, e.getMessage());
            MsmlDocument errorDoc = MsmlBuilder.resultError(400, "Bad MSML: " + e.getMessage()).build();
            SipResponse response = request.createResponse(200, "OK");
            response.getHeaders().setContentType(MsmlDocument.CONTENT_TYPE);
            response.setBody(MsmlSerializer.serialize(errorDoc));
            return Mono.just(response);
        } catch (Exception e) {
            LOG.error("Unexpected error executing MSML on Call-ID {}: {}", callId, e.getMessage(), e);
            MsmlDocument errorDoc = MsmlBuilder.resultError(500, "Server Error: " + e.getMessage()).build();
            SipResponse response = request.createResponse(200, "OK");
            response.getHeaders().setContentType(MsmlDocument.CONTENT_TYPE);
            response.setBody(MsmlSerializer.serialize(errorDoc));
            return Mono.just(response);
        }
    }

    /**
     * Handles initial SIP INVITE establishing the media stream and optional initial MSML dialog.
     */
    @OnInvite
    public Mono<SipResponse> onInvite(SipRequest request,
                                      @SipCallId String callId,
                                      @Nullable @SipBody String body,
                                      @Nullable @SipHeader("Content-Type") String contentType) {
        String user = request.getUri() != null ? request.getUri().getUser() : null;
        boolean isMsmlEndpoint = user != null && (user.equalsIgnoreCase("msml") || user.equalsIgnoreCase("ms"));

        if (!isMsmlEndpoint && !isMsmlPayload(contentType, body)) {
            // Not addressed to MSML service
            return Mono.empty();
        }

        LOG.info("MSML server processing INVITE for Call-ID: {} (URI: {})", callId, request.getUri());

        if (request.getRemoteAddress() != null) {
            callRemoteAddresses.put(callId, request.getRemoteAddress());
        }

        try {
            RtpMediaSession session = rtpMediaManager.getOrCreateSession(callId);
            String answerSdp = sdpNegotiator.createAnswer(body, session.getLocalPort());

            // If MSML document was supplied in body, parse and execute initial command
            if (body != null && body.contains("<msml")) {
                int msmlStart = body.indexOf("<msml");
                int msmlEnd = body.indexOf("</msml>") + 7;
                if (msmlStart >= 0 && msmlEnd > msmlStart) {
                    String msmlXml = body.substring(msmlStart, msmlEnd);
                    try {
                        MsmlDocument doc = parser.parse(msmlXml);
                        for (MsmlCommand cmd : doc.commands()) {
                            executeCommand(cmd, callId);
                        }
                    } catch (Exception e) {
                        LOG.warn("Failed executing initial MSML command in INVITE: {}", e.getMessage());
                    }
                }
            }

            SipResponse response = request.createResponse(200, "OK");
            response.getHeaders().setContentType("application/sdp");
            response.setBody(answerSdp);
            return Mono.just(response);

        } catch (IOException e) {
            LOG.error("Failed creating RTP session for Call-ID: {}", callId, e);
            return Mono.just(request.createResponse(500, "Internal Server Error"));
        }
    }

    /**
     * Handles SIP BYE releasing all media and dialog resources associated with the call session.
     */
    @OnBye
    public Mono<SipResponse> onBye(SipRequest request, @SipCallId String callId) {
        LOG.info("MSML server received BYE for Call-ID: {}", callId);
        callRemoteAddresses.remove(callId);
        mediaEngine.stopDialog("dlg-" + callId);
        rtpMediaManager.terminateSession(callId);
        return Mono.just(request.createResponse(200, "OK"));
    }

    private MsmlResult executeCommand(MsmlCommand cmd, String callId) {
        return switch (cmd) {
            case MsmlCommand.DialogStart ds -> {
                RtpMediaSession session = rtpMediaManager.findSession(callId)
                        .orElseGet(() -> rtpMediaManager.findSession(ds.target().id()).orElse(null));
                mediaEngine.executeDialog(ds, session);
                yield MsmlResult.ok(ds.id());
            }
            case MsmlCommand.DialogEnd de -> {
                mediaEngine.stopDialog(de.id());
                yield MsmlResult.ok(de.id());
            }
            case MsmlCommand.CreateConference cc -> mediaEngine.createConference(cc);
            case MsmlCommand.ModifyConference mc -> mediaEngine.modifyConference(mc);
            case MsmlCommand.DestroyConference dc -> mediaEngine.destroyConference(dc);
            case MsmlCommand.Join j -> mediaEngine.join(j);
            case MsmlCommand.Unjoin uj -> mediaEngine.unjoin(uj);
            case MsmlCommand.Audit a -> mediaEngine.audit(a);
        };
    }

    private void handleMediaEvent(MsmlEvent event) {
        LOG.debug("MSML engine event: {} for target id: {}", event.name(), event.id());
        // If event corresponds to an active connection, send in-dialog INFO event if client is configured
        if (sipClient != null && event.id() != null) {
            String callId = event.id().startsWith("dlg-") ? event.id().substring(4) : event.id();
            InetSocketAddress remote = callRemoteAddresses.get(callId);
            if (remote != null) {
                String eventXml = MsmlSerializer.serialize(event);
                SipRequest infoReq = SipRequest.builder(net.pilgrim.sip.model.SipMethod.INFO, "sip:client@" + remote.getHostString())
                        .callId(callId)
                        .contentType(MsmlDocument.CONTENT_TYPE)
                        .body(eventXml)
                        .build();
                sipClient.send(infoReq, remote).subscribe(
                        resp -> LOG.debug("Delivered MSML event {} to {}", event.name(), remote),
                        err -> LOG.debug("Could not deliver MSML event to {}: {}", remote, err.getMessage())
                );
            }
        }
    }

    private static boolean isMsmlPayload(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().contains("msml")) {
            return true;
        }
        return body != null && body.trim().startsWith("<msml");
    }
}
