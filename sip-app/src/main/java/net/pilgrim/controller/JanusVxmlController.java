package net.pilgrim.controller;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.rtp.media.RtpMediaManager;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.sip.sdp.SdpMessage;
import net.pilgrim.sip.sdp.SdpNegotiator;
import net.pilgrim.sip.sdp.SdpParser;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.transport.SipNettyServer;
import net.pilgrim.vxml.runtime.VxmlSession;
import net.pilgrim.vxml.VxmlSessionFactory;
import net.pilgrim.vxml.media.RtpVxmlMedia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SIP Controller for Janus Gateway integration with VXML.
 * Handles INVITE requests from Janus WebRTC Gateway and bridges them to VXML sessions.
 */
@Singleton
@SipController
@Requires(property = "janus.vxml.enabled", value = "true", defaultValue = "false")
public class JanusVxmlController extends BaseSipController {

    private static final Logger LOG = LoggerFactory.getLogger(JanusVxmlController.class);
    private static final SdpNegotiator SDP_NEGOTIATOR = new SdpNegotiator();
    private static final SdpParser SDP_PARSER = new SdpParser();

    private final RtpMediaManager rtpMediaManager;
    private final VxmlSessionFactory vxmlSessionFactory;

    // Map of call IDs to active VXML sessions
    private final Map<String, VxmlSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, RtpVxmlMedia> activeMedia = new ConcurrentHashMap<>();

    @Inject
    public JanusVxmlController(
            RtpMediaManager rtpMediaManager,
            VxmlSessionFactory vxmlSessionFactory,
            @Nullable SipNettyServer sipServer,
            @Nullable SipServerConfiguration serverConfig) {
        super(sipServer, serverConfig);
        this.rtpMediaManager = rtpMediaManager;
        this.vxmlSessionFactory = vxmlSessionFactory;
    }

    /**
     * Handles INVITE requests from Janus Gateway.
     * Creates RTP session, bridges to VXML, and returns 200 OK with SDP answer.
     */
    @OnInvite
    public Flux<SipResponse> onInvite(
            SipRequest request,
            @SipCallId String callId,
            @SipBody String sdpOffer,
            SipSession sipSession) {

        LOG.info("Janus INVITE received for Call-ID: {} from: {} to: {}",
                callId, request.getFrom(), request.getTo());

        if (sdpOffer == null || sdpOffer.isBlank()) {
            LOG.warn("No SDP offer in INVITE for Call-ID: {}", callId);
            return Flux.just(SipResponse.badRequest(request, "SDP offer required"));
        }

        try {
            // Parse the SDP offer from Janus
            SdpMessage parsedOffer = SDP_PARSER.parse(sdpOffer);
            SdpMessage.MediaDescription audio = parsedOffer.findFirstAudioMedia();

            if (audio == null || audio.getPort() <= 0) {
                LOG.warn("No valid audio media in SDP offer for Call-ID: {}", callId);
                return Flux.just(new SipResponse(488, "Not Acceptable Here"));
            }

            // Create RTP media session
            RtpMediaSession rtpSession = rtpMediaManager.createSession(callId);
            int localAudioPort = rtpSession.getLocalPort();

            LOG.info("Created RTP session on port {} for Call-ID: {}", localAudioPort, callId);

            // Get remote address from SDP
            String remoteHost = Optional.ofNullable(parsedOffer.getConnection())
                    .map(c -> c.split("\\s+"))
                    .filter(parts -> parts.length >= 3)
                    .map(parts -> parts[2])
                    .orElse("127.0.0.1");

            InetSocketAddress remoteAddress = new InetSocketAddress(
                    InetAddress.getByName(remoteHost),
                    audio.getPort());

            rtpSession.setRemoteAddress(remoteAddress);
            LOG.info("Set remote RTP address to {}:{} for Call-ID: {}",
                    remoteHost, audio.getPort(), callId);

            // Create RTP-VXML media bridge
            RtpVxmlMedia rtpVxmlMedia = new RtpVxmlMedia(rtpSession);
            activeMedia.put(callId, rtpVxmlMedia);

            // Determine VXML document URL from request
            String vxmlUrl = determineVxmlUrl(request);
            LOG.info("Starting VXML session with URL: {} for Call-ID: {}", vxmlUrl, callId);

            // Create and start VXML session
            VxmlSession vxmlSession = vxmlSessionFactory.createSession(vxmlUrl, rtpVxmlMedia);
            activeSessions.put(callId, vxmlSession);

            // Start VXML interpreter in background
            new Thread(vxmlSession::start, "vxml-session-" + callId).start();
            LOG.info("VXML session started for Call-ID: {}", callId);

            // Generate SDP answer
            String advertisedIp = resolveAdvertisedIp();
            String sdpAnswer = SDP_NEGOTIATOR.createAnswer(sdpOffer, localAudioPort, advertisedIp);

            if (sipSession != null) {
                sipSession.setAttribute("sdpAnswer", sdpAnswer);
                sipSession.setAttribute("sdpOffer", sdpOffer);
            }

            // Build 200 OK response
            String contactUri = buildContactUri(request);
            SipResponse ok = SipResponse.ok(request, sdpAnswer, "application/sdp");
            ok.getHeaders().setContact(contactUri);

            LOG.info("Sending 200 OK with SDP answer for Call-ID: {}", callId);

            // Return 180 Ringing followed by 200 OK
            SipResponse ringing = SipResponse.ringing(request);
            return Flux.concat(
                    Mono.just(ringing),
                    Mono.just(ok).delayElement(Duration.ofMillis(100))
            );

        } catch (Exception e) {
            LOG.error("Failed to handle Janus INVITE for Call-ID: {}", callId, e);
            cleanup(callId);
            return Flux.just(SipResponse.serverError(request, e.getMessage()));
        }
    }

    /**
     * Handles ACK confirming call setup.
     */
    @OnAck
    public void onAck(
            SipRequest request,
            @SipCallId String callId,
            @SipBody String ackSdpAnswer) {

        LOG.info("ACK received for Call-ID: {}", callId);

        if (ackSdpAnswer != null && !ackSdpAnswer.isBlank()) {
            LOG.info("ACK contains SDP answer ({} bytes) for Call-ID: {}",
                    ackSdpAnswer.length(), callId);
        }
    }

    /**
     * Handles BYE request to terminate the call and VXML session.
     */
    @OnBye
    public SipResponse onBye(
            SipRequest request,
            @SipCallId String callId) {

        LOG.info("BYE received for Call-ID: {}", callId);
        cleanup(callId);
        return SipResponse.ok(request);
    }

    /**
     * Handles CANCEL request to abort call setup.
     */
    @OnCancel
    public SipResponse onCancel(
            SipRequest request,
            @SipCallId String callId) {

        LOG.info("CANCEL received for Call-ID: {}", callId);
        cleanup(callId);
        return SipResponse.ok(request);
    }

    /**
     * Handles OPTIONS request for capability querying.
     */
    @OnOptions
    public SipResponse onOptions(SipRequest request) {
        LOG.info("OPTIONS request from {}", request.getFrom());
        SipResponse response = SipResponse.ok(request);
        response.getHeaders().set(SipHeaders.ALLOW, "INVITE, ACK, BYE, CANCEL, OPTIONS");
        response.getHeaders().set(SipHeaders.SUPPORTED, "replaces");
        return response;
    }

    /**
     * Determines the VXML document URL from the SIP request.
     * Can use To URI, request URI, or a default.
     */
    private String determineVxmlUrl(SipRequest request) {
        // Check for X-VXML-URL header
        String headerUrl = request.getHeaders().get("X-VXML-URL");
        if (headerUrl != null && !headerUrl.isBlank()) {
            return headerUrl;
        }

        // Extract from To URI (e.g., sip:vxml-app@domain -> look up vxml-app)
        String toUri = request.getTo();
        if (toUri != null) {
            // Simple extraction: get user part before @
            String user = toUri.replaceAll("^sip:", "").split("[@;]")[0];
            if (user != null && !user.isBlank()) {
                // Map specific users to VXML URLs
                // For now, use a default VXML document
                return "http://localhost:8080/vxml/" + user + ".vxml";
            }
        }

        // Default VXML application
        return "http://localhost:8080/vxml/welcome.vxml";
    }

    /**
     * Cleans up resources for a call.
     */
    private void cleanup(String callId) {
        LOG.debug("Cleaning up resources for Call-ID: {}", callId);

        // Stop and remove VXML session
        VxmlSession vxmlSession = activeSessions.remove(callId);
        if (vxmlSession != null) {
            try {
                vxmlSession.terminate();
                LOG.debug("Stopped VXML session for Call-ID: {}", callId);
            } catch (Exception e) {
                LOG.warn("Error stopping VXML session for Call-ID: {}", callId, e);
            }
        }

        // Close RTP-VXML media bridge
        RtpVxmlMedia media = activeMedia.remove(callId);
        if (media != null) {
            try {
                media.stopAudio();
                LOG.debug("Closed RTP media for Call-ID: {}", callId);
            } catch (Exception e) {
                LOG.warn("Error closing RTP media for Call-ID: {}", callId, e);
            }
        }

        // Remove RTP session from manager
        rtpMediaManager.terminateSession(callId);
    }

    /**
     * Gets active VXML sessions (for monitoring/debugging).
     */
    public Map<String, VxmlSession> getActiveSessions() {
        return activeSessions;
    }
}
