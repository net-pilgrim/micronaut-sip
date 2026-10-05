package net.pilgrim.netann.controller;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.netann.model.AnnouncementParams;
import net.pilgrim.netann.service.AnnouncementAudioLoader;
import net.pilgrim.netann.service.AnnouncementPlayer;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.rtp.media.RtpMediaManager;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.sip.sdp.SdpMessage;
import net.pilgrim.sip.sdp.SdpNegotiator;
import net.pilgrim.sip.sdp.SdpParser;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import net.pilgrim.sip.transport.SipNettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.FileNotFoundException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements RFC 4240 Basic Network Media Services with SIP: Announcement Service (annc).
 *
 * Request-URI format:
 *   sip:annc@ms.example.net;play=<url>[;repeat=N|forever][;delay=ms][;duration=ms][;locale=xx]
 */
@SipController
public class AnnouncementController {

    private static final Logger LOG = LoggerFactory.getLogger(AnnouncementController.class);

    private final RtpMediaManager rtpMediaManager;
    private final AnnouncementAudioLoader audioLoader;
    private final SipNettyServer sipServer;
    private final SipSessionManager sessionManager;
    private final SdpNegotiator sdpNegotiator = new SdpNegotiator();
    private final SdpParser sdpParser = new SdpParser();

    private final Map<String, AnnouncementPlayer> activePlayers = new ConcurrentHashMap<>();

    @Inject
    public AnnouncementController(RtpMediaManager rtpMediaManager,
                                  AnnouncementAudioLoader audioLoader,
                                  SipNettyServer sipServer,
                                  SipSessionManager sessionManager) {
        this.rtpMediaManager = rtpMediaManager != null ? rtpMediaManager : new RtpMediaManager();
        this.audioLoader = audioLoader != null ? audioLoader : new AnnouncementAudioLoader();
        this.sipServer = sipServer;
        this.sessionManager = sessionManager;
    }

    /**
     * Handles RFC 4240 Announcement Service (sip:annc@...).
     */
    @OnInvite("annc")
    public Mono<SipResponse> onAnnouncementInvite(SipRequest request,
                                                  @SipCallId String callId,
                                                  @SipBody String sdpOffer,
                                                  SipSession session) {
        LOG.info("Received RFC 4240 annc INVITE for Call-ID: {} (URI: {})", callId, request.getUri());

        // 1. Parse announcement parameters from Request-URI
        AnnouncementParams params;
        try {
            params = AnnouncementParams.parse(request.getUri());
        } catch (IllegalArgumentException e) {
            LOG.warn("Failed parsing annc URI parameters for Call-ID: {}: {}", callId, e.getMessage());
            return Mono.just(request.createResponse(SipStatus.BAD_REQUEST, e.getMessage()));
        }

        // 2. Validate mandatory play= parameter per RFC 4240 §3
        if (params.getPlay() == null || params.getPlay().isBlank()) {
            LOG.warn("Missing mandatory play= parameter for Call-ID: {}", callId);
            return Mono.just(request.createResponse(SipStatus.BAD_REQUEST, "Mandatory play parameter missing"));
        }

        // 3. Load and decode audio prompt
        byte[] pcmAudio;
        try {
            pcmAudio = audioLoader.loadAudio(params.getPlay());
        } catch (FileNotFoundException e) {
            LOG.warn("Announcement content not found for '{}' (Call-ID: {})", params.getPlay(), callId);
            return Mono.just(request.createResponse(SipStatus.NOT_FOUND, "Announcement content not found"));
        } catch (Exception e) {
            LOG.warn("Announcement content could not be retrieved for '{}' (Call-ID: {}): {}",
                    params.getPlay(), callId, e.getMessage());
            return Mono.just(request.createResponse(SipStatus.BAD_REQUEST, "Announcement content could not be retrieved"));
        }

        // 4. Create RTP media session
        int localAudioPort = 49170;
        try {
            RtpMediaSession mediaSession = rtpMediaManager.createSession(callId);
            localAudioPort = mediaSession.getLocalPort();
        } catch (Exception e) {
            LOG.warn("Failed creating RTP media session for Call-ID: {}", callId, e);
        }

        // 5. Generate SDP Answer
        String sdpAnswer = sdpNegotiator.createAnswer(sdpOffer != null ? sdpOffer : "", localAudioPort);

        session.setState(SipSession.State.EARLY);
        session.setAttribute("anncParams", params);
        session.setAttribute("pcmAudio", pcmAudio);
        session.setAttribute("sdpOffer", sdpOffer);
        session.setAttribute("originalInvite", request);

        int contactPort = (request.getRemoteAddress() != null) ? request.getRemoteAddress().getPort() : 5060;
        SipResponse ok = SipResponse.ok(request, sdpAnswer, "application/sdp");
        ok.getHeaders().setContact("<sip:annc@127.0.0.1:" + contactPort + ">");
        session.setAttribute("originalOk", ok);

        return Mono.just(ok);
    }

    /**
     * Fallback for unknown service indicators (RFC 4240 §2: "If the media server cannot perform
     * the requested service or does not recognize the service indicator, it MUST respond with 488 NOT ACCEPTABLE HERE").
     */
    @OnInvite
    public Mono<SipResponse> onUnknownServiceInvite(SipRequest request) {
        LOG.warn("Received INVITE for unsupported service '{}'. Returning 488 Not Acceptable Here per RFC 4240.",
                request.getUri());
        return Mono.just(request.createResponse(488, "Not Acceptable Here"));
    }

    /**
     * Handles ACK confirming call setup: connects the RTP remote address and starts audio playback.
     */
    @OnAck
    public void onAck(SipRequest request,
                      @SipCallId String callId,
                      SipSession session) {
        LOG.info("Received ACK for annc Call-ID: {}, starting RTP playback.", callId);
        if (session != null) {
            session.setState(SipSession.State.CONFIRMED);
        }

        Optional<RtpMediaSession> mediaSessionOpt = rtpMediaManager.findSession(callId);
        if (mediaSessionOpt.isEmpty()) {
            LOG.warn("No RTP media session found for Call-ID: {} on ACK", callId);
            return;
        }
        RtpMediaSession mediaSession = mediaSessionOpt.get();

        // Configure remote RTP address from SDP offer
        String remoteSdp = (session != null) ? session.getAttribute("sdpOffer") : null;
        if (remoteSdp != null && !remoteSdp.isBlank()) {
            try {
                SdpMessage parsed = sdpParser.parse(remoteSdp);
                SdpMessage.MediaDescription audio = parsed.findFirstAudioMedia();
                if (audio != null && audio.getPort() > 0) {
                    String host = parseConnectionHost(parsed.getConnection())
                            .orElse((request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null)
                                    ? request.getRemoteAddress().getAddress().getHostAddress() : "127.0.0.1");
                    mediaSession.setRemoteAddress(new InetSocketAddress(host, audio.getPort()));
                    LOG.info("Configured remote RTP destination {}:{} for Call-ID: {}", host, audio.getPort(), callId);
                }
            } catch (Exception e) {
                LOG.warn("Failed parsing SDP offer for Call-ID: {}", callId, e);
            }
        }

        byte[] pcmAudio = (session != null) ? session.getAttribute("pcmAudio") : null;
        AnnouncementParams params = (session != null) ? session.getAttribute("anncParams") : null;
        SipRequest originalInvite = (session != null) ? session.getAttribute("originalInvite") : null;
        SipResponse originalOk = (session != null) ? session.getAttribute("originalOk") : null;

        if (pcmAudio == null || pcmAudio.length == 0) {
            LOG.info("No audio data to play for Call-ID: {}, terminating call with BYE.", callId);
            terminateAndSendBye(callId, session, originalInvite, originalOk);
            return;
        }

        // Create and start RTP audio player
        AnnouncementPlayer player = new AnnouncementPlayer(
                mediaSession,
                pcmAudio,
                params,
                () -> {
                    LOG.info("Announcement finished for Call-ID: {}. Terminating call with BYE.", callId);
                    terminateAndSendBye(callId, session, originalInvite, originalOk);
                }
        );

        activePlayers.put(callId, player);
        player.start();
    }

    /**
     * Handles BYE from client terminating the call early.
     */
    @OnBye
    public Mono<SipResponse> onBye(SipRequest request,
                                   @SipCallId String callId,
                                   SipSession session) {
        LOG.info("Received BYE for Call-ID: {}, stopping playback and terminating session.", callId);
        cleanupCall(callId, session);
        return Mono.just(SipResponse.ok(request));
    }

    /**
     * Handles CANCEL request from client terminating call setup.
     */
    @OnCancel
    public void onCancel(SipRequest request,
                         @SipCallId String callId,
                         SipSession session) {
        LOG.info("Received CANCEL for Call-ID: {}, cancelling playback.", callId);
        cleanupCall(callId, session);
    }

    /**
     * Handles OPTIONS request for capability querying.
     */
    @OnOptions
    public SipResponse onOptions(SipRequest request) {
        SipResponse response = SipResponse.ok(request);
        response.getHeaders().set(SipHeaders.ALLOW, "INVITE, ACK, BYE, CANCEL, OPTIONS");
        response.getHeaders().set(SipHeaders.SUPPORTED, "100rel");
        return response;
    }

    private void cleanupCall(String callId, SipSession session) {
        AnnouncementPlayer player = activePlayers.remove(callId);
        if (player != null) {
            player.stop();
        }
        if (session != null) {
            session.setState(SipSession.State.TERMINATED);
        }
        rtpMediaManager.terminateSession(callId);
    }

    private void terminateAndSendBye(String callId,
                                     SipSession session,
                                     SipRequest originalInvite,
                                     SipResponse originalOk) {
        cleanupCall(callId, session);

        if (originalInvite == null || originalOk == null || sipServer == null) {
            LOG.warn("Cannot send BYE for Call-ID: {}: missing original dialog state or server", callId);
            return;
        }

        try {
            String targetUri = originalInvite.getContact();
            if (targetUri == null || targetUri.isEmpty()) {
                targetUri = originalInvite.getFrom();
            }
            if (targetUri != null) {
                targetUri = targetUri.replaceAll("^<|>$", "").split(";")[0];
            } else {
                targetUri = originalInvite.getUri().toString();
            }

            SipRequest bye = new SipRequest(SipMethod.BYE, SipUri.parse(targetUri));
            bye.setRemoteAddress(originalInvite.getRemoteAddress());
            bye.setTransport(originalInvite.getTransport());

            // In-dialog BYE: To is remote (original From), From is local (original To with tag)
            bye.getHeaders().setTo(originalInvite.getFrom());
            bye.getHeaders().setFrom(originalOk.getTo());
            bye.getHeaders().setCallId(callId);
            bye.getHeaders().setCSeq("2 BYE");
            bye.getHeaders().setMaxForwards(70);
            bye.getHeaders().setContentLength(0);

            String host = "127.0.0.1";
            int port = (originalInvite.getTransport() == SipTransport.TCP) ? sipServer.getTcpPort() : sipServer.getUdpPort();
            String proto = (originalInvite.getTransport() == SipTransport.TCP) ? "TCP" : "UDP";
            String branch = "z9hG4bK-bye-" + UUID.randomUUID().toString().substring(0, 8);
            bye.getHeaders().set(SipHeaders.VIA, "SIP/2.0/" + proto + " " + host + ":" + port + ";branch=" + branch + ";rport");

            LOG.info("Sending BYE for completed announcement Call-ID: {} to {}", callId, targetUri);
            sipServer.send(bye, originalInvite.getRemoteAddress());
        } catch (Exception e) {
            LOG.warn("Failed sending BYE for completed announcement Call-ID: {}: {}", callId, e.getMessage(), e);
        }
    }

    private Optional<String> parseConnectionHost(String connectionLine) {
        if (connectionLine == null || connectionLine.isBlank()) {
            return Optional.empty();
        }
        String[] parts = connectionLine.trim().split("\\s+");
        if (parts.length < 3 || parts[2].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parts[2].trim());
    }

    public Map<String, AnnouncementPlayer> getActivePlayers() {
        return activePlayers;
    }
}
