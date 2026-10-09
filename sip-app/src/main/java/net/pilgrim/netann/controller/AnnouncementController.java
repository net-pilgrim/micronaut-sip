package net.pilgrim.netann.controller;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import net.pilgrim.netann.config.NetannConfiguration;
import net.pilgrim.netann.model.AnnouncementParams;
import net.pilgrim.netann.service.AnnouncementAudioLoader;
import net.pilgrim.netann.service.AnnouncementPlayer;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.config.SipServerConfiguration;
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
import reactor.core.scheduler.Schedulers;

import java.io.FileNotFoundException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

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
    private final NetannConfiguration config;
    private final SdpNegotiator sdpNegotiator = new SdpNegotiator();
    private final SdpParser sdpParser = new SdpParser();

    private final Map<String, AnnouncementPlayer> activePlayers = new ConcurrentHashMap<>();
    private final Map<String, String> callIdToClientIp = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> activeCallsPerIp = new ConcurrentHashMap<>();

    @Inject
    public AnnouncementController(RtpMediaManager rtpMediaManager,
                                  @Nullable AnnouncementAudioLoader audioLoader,
                                  @Nullable SipNettyServer sipServer,
                                  @Nullable SipSessionManager sessionManager,
                                  @Nullable NetannConfiguration config) {
        this.rtpMediaManager = Optional.ofNullable(rtpMediaManager).orElseGet(RtpMediaManager::new);
        this.audioLoader = Optional.ofNullable(audioLoader).orElseGet(AnnouncementAudioLoader::new);
        this.sipServer = sipServer;
        this.sessionManager = sessionManager;
        this.config = Optional.ofNullable(config)
                .or(() -> Optional.ofNullable(this.audioLoader.getConfiguration()))
                .orElseGet(NetannConfiguration::new);
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

        // 0. Check concurrency limits (total active & per-IP)
        if (activePlayers.size() >= config.getMaxActiveAnnouncements()) {
            LOG.warn("Max active announcements limit reached ({}), rejecting Call-ID: {}",
                    config.getMaxActiveAnnouncements(), callId);
            SipResponse overloaded = request.createResponse(503, "Service Unavailable");
            overloaded.getHeaders().set("Retry-After", "10");
            return Mono.just(overloaded);
        }

        String clientIp = Optional.ofNullable(request.getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .map(InetAddress::getHostAddress)
                .orElse("127.0.0.1");

        AtomicInteger ipCounter = activeCallsPerIp.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        if (ipCounter.incrementAndGet() > config.getMaxAnnouncementsPerIp()) {
            ipCounter.decrementAndGet();
            LOG.warn("Max announcements per IP limit reached for {} ({}), rejecting Call-ID: {}",
                    clientIp, config.getMaxAnnouncementsPerIp(), callId);
            SipResponse overloaded = request.createResponse(503, "Service Unavailable");
            overloaded.getHeaders().set("Retry-After", "10");
            return Mono.just(overloaded);
        }
        callIdToClientIp.put(callId, clientIp);

        // 1. Parse announcement parameters from Request-URI
        AnnouncementParams params;
        try {
            params = AnnouncementParams.parse(request.getUri());
        } catch (IllegalArgumentException e) {
            releaseClientIp(callId);
            LOG.warn("Failed parsing annc URI parameters for Call-ID: {}: {}", callId, e.getMessage());
            return Mono.just(request.createResponse(SipStatus.BAD_REQUEST, e.getMessage()));
        }

        // 2. Validate mandatory play= parameter per RFC 4240 §3
        if (params.getPlay() == null || params.getPlay().isBlank()) {
            releaseClientIp(callId);
            LOG.warn("Missing mandatory play= parameter for Call-ID: {}", callId);
            return Mono.just(request.createResponse(SipStatus.BAD_REQUEST, "Mandatory play parameter missing"));
        }

        // 3. Load and decode audio prompt asynchronously off Netty event loop
        return Mono.fromCallable(() -> audioLoader.loadAudio(params.getPlay()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(pcmAudio -> {
                    // 4. Create RTP media session
                    int localAudioPort = Optional.ofNullable(callId)
                            .flatMap(id -> {
                                try {
                                    return Optional.of(rtpMediaManager.createSession(id).getLocalPort());
                                } catch (Exception e) {
                                    LOG.warn("Failed creating RTP media session for Call-ID: {}", id, e);
                                    return Optional.empty();
                                }
                            })
                            .orElse(49170);

                    // 5. Generate SDP Answer (or local offer if late-offer INVITE) with advertised IP
                    String advertisedIp = resolveAdvertisedIp();
                    String sdpAnswer = Optional.ofNullable(sdpOffer)
                            .filter(Predicate.not(String::isBlank))
                            .map(offer -> sdpNegotiator.createAnswer(offer, localAudioPort, advertisedIp))
                            .orElseGet(() -> sdpNegotiator.createOffer(localAudioPort, advertisedIp));

                    Optional.ofNullable(session).ifPresent(s -> {
                        s.handleInvite(request, sdpOffer);
                        s.setState(SipSession.State.EARLY);
                        s.setAttribute("anncParams", params);
                        s.setAttribute("pcmAudio", pcmAudio);
                        s.setAttribute("sdpOffer", sdpOffer);
                        s.setAttribute("originalInvite", request);
                    });

                    int serverPort = Optional.ofNullable(sipServer)
                            .map(SipNettyServer::getTransportRegistry)
                            .map(reg -> reg.resolveServerPort(request, sipServer.getConfiguration()))
                            .orElseGet(() -> (request.getTransport() == SipTransport.TCP)
                                    ? Optional.ofNullable(sipServer).map(SipNettyServer::getTcpPort).orElse(5060)
                                    : Optional.ofNullable(sipServer).map(SipNettyServer::getUdpPort).orElse(5060));

                    SipResponse ok = SipResponse.ok(request, sdpAnswer, "application/sdp");
                    String contactUri = Optional.ofNullable(sipServer)
                            .map(SipNettyServer::getTransportRegistry)
                            .map(reg -> reg.formatContactUri(request, advertisedIp, serverPort, "annc"))
                            .orElseGet(() -> {
                                boolean isTls = request.getTransport() == SipTransport.TLS;
                                String scheme = isTls ? "sips" : "sip";
                                String transportParam = Optional.ofNullable(request.getTransport())
                                        .filter(t -> t != SipTransport.UDP)
                                        .map(t -> ";transport=" + t.name().toLowerCase())
                                        .orElse("");
                                return "<" + scheme + ":annc@" + advertisedIp + ":" + serverPort + transportParam + ">";
                            });
                    ok.getHeaders().setContact(contactUri);
                    Optional.ofNullable(session).ifPresent(s -> s.setAttribute("originalOk", ok));

                    return Mono.just(ok);
                })
                .onErrorResume(FileNotFoundException.class, e -> {
                    releaseClientIp(callId);
                    LOG.warn("Announcement content not found for '{}' (Call-ID: {})", params.getPlay(), callId);
                    return Mono.just(request.createResponse(SipStatus.NOT_FOUND, "Announcement content not found"));
                })
                .onErrorResume(SecurityException.class, e -> {
                    releaseClientIp(callId);
                    LOG.warn("Security violation for announcement '{}' (Call-ID: {}): {}",
                            params.getPlay(), callId, e.getMessage());
                    return Mono.just(request.createResponse(SipStatus.FORBIDDEN, "Forbidden: " + e.getMessage()));
                })
                .onErrorResume(Exception.class, e -> {
                    releaseClientIp(callId);
                    LOG.warn("Announcement content could not be retrieved for '{}' (Call-ID: {}): {}",
                            params.getPlay(), callId, e.getMessage());
                    return Mono.just(request.createResponse(SipStatus.BAD_REQUEST, "Announcement content could not be retrieved"));
                });
    }

    /**
     * Fallback for conference service indicator (RFC 4240 §2: "If the media server cannot perform
     * the requested service or does not recognize the service indicator, it MUST respond with 488 NOT ACCEPTABLE HERE").
     */
    @OnInvite("conf")
    public Mono<SipResponse> onConfInvite(SipRequest request) {
        LOG.warn("Received INVITE for unsupported service '{}'. Returning 488 Not Acceptable Here per RFC 4240.",
                request.getUri());
        return Mono.just(request.createResponse(488, "Not Acceptable Here"));
    }


    /**
     * Handles ACK confirming call setup: connects the RTP remote address and starts audio playback.
     */
    @OnAck("annc")
    public void onAck(SipRequest request,
                      @SipCallId String callId,
                      SipSession session) {
        LOG.info("Received ACK for annc Call-ID: {}, starting RTP playback.", callId);
        Optional.ofNullable(session).ifPresent(s -> s.handleAck(request, null));

        Optional<RtpMediaSession> mediaSessionOpt = rtpMediaManager.findSession(callId);
        if (mediaSessionOpt.isEmpty()) {
            LOG.warn("No RTP media session found for Call-ID: {} on ACK", callId);
            return;
        }
        RtpMediaSession mediaSession = mediaSessionOpt.get();

        // Configure remote RTP address from SDP offer
        Optional.ofNullable(session)
                .map(s -> (String) s.getAttribute("sdpOffer"))
                .filter(Predicate.not(String::isBlank))
                .ifPresent(remoteSdp -> {
                    try {
                        SdpMessage parsed = sdpParser.parse(remoteSdp);
                        Optional.ofNullable(parsed.findFirstAudioMedia())
                                .filter(audio -> audio.getPort() > 0)
                                .ifPresent(audio -> {
                                    String host = parseConnectionHost(parsed.getConnection())
                                            .or(() -> Optional.ofNullable(request.getRemoteAddress())
                                                    .map(InetSocketAddress::getAddress)
                                                    .map(InetAddress::getHostAddress))
                                            .orElse("127.0.0.1");
                                    mediaSession.setRemoteAddress(new InetSocketAddress(host, audio.getPort()));
                                    LOG.info("Configured remote RTP destination {}:{} for Call-ID: {}", host, audio.getPort(), callId);
                                });
                    } catch (Exception e) {
                        LOG.warn("Failed parsing SDP offer for Call-ID: {}", callId, e);
                    }
                });

        byte[] pcmAudio = Optional.ofNullable(session).map(s -> (byte[]) s.getAttribute("pcmAudio")).orElse(null);
        AnnouncementParams params = Optional.ofNullable(session).map(s -> (AnnouncementParams) s.getAttribute("anncParams")).orElse(null);
        SipRequest originalInvite = Optional.ofNullable(session).map(s -> (SipRequest) s.getAttribute("originalInvite")).orElse(null);
        SipResponse originalOk = Optional.ofNullable(session).map(s -> (SipResponse) s.getAttribute("originalOk")).orElse(null);

        if (pcmAudio == null || pcmAudio.length == 0) {
            LOG.info("No audio data to play for Call-ID: {}, terminating call with BYE.", callId);
            terminateAndSendBye(callId, session, originalInvite, originalOk);
            return;
        }

        // Create and start RTP audio player with configured duration and repeat limits
        AnnouncementPlayer player = new AnnouncementPlayer(
                mediaSession,
                pcmAudio,
                params,
                config.getMaxDurationSeconds() * 1000L,
                config.getMaxRepeatCount(),
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
    @OnBye("annc")
    public Mono<SipResponse> onBye(SipRequest request,
                                   @SipCallId String callId,
                                   SipSession session) {
        LOG.info("Received BYE for Call-ID: {}, stopping playback and terminating session.", callId);
        Optional.ofNullable(session).ifPresent(s -> s.handleBye(request));
        cleanupCall(callId, session);
        return Mono.just(SipResponse.ok(request));
    }

    /**
     * Handles CANCEL request from client terminating call setup.
     */
    @OnCancel("annc")
    public void onCancel(SipRequest request,
                         @SipCallId String callId,
                         SipSession session) {
        LOG.info("Received CANCEL for Call-ID: {}, cancelling playback.", callId);
        Optional.ofNullable(session).ifPresent(s -> s.handleCancel(request));
        cleanupCall(callId, session);
    }

    /**
     * Handles OPTIONS request for capability querying.
     */
    @OnOptions("annc")
    public SipResponse onOptions(SipRequest request) {
        SipResponse response = SipResponse.ok(request);
        response.getHeaders().set(SipHeaders.ALLOW, "INVITE, ACK, BYE, CANCEL, OPTIONS");
        response.getHeaders().set(SipHeaders.SUPPORTED, "100rel");
        return response;
    }

    private void releaseClientIp(String callId) {
        Optional.ofNullable(callIdToClientIp.remove(callId)).ifPresent(clientIp -> {
            AtomicInteger counter = activeCallsPerIp.get(clientIp);
            if (counter != null && counter.decrementAndGet() <= 0) {
                activeCallsPerIp.remove(clientIp, counter);
            }
        });
    }

    private void cleanupCall(String callId, SipSession session) {
        releaseClientIp(callId);
        Optional.ofNullable(activePlayers.remove(callId)).ifPresent(AnnouncementPlayer::stop);
        Optional.ofNullable(session)
                .filter(Predicate.not(SipSession::isTerminated))
                .ifPresent(s -> s.setState(SipSession.State.TERMINATED));
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
            String targetUri = Optional.ofNullable(originalInvite.getContact())
                    .filter(Predicate.not(String::isEmpty))
                    .or(() -> Optional.ofNullable(originalInvite.getFrom()))
                    .map(uri -> uri.replaceAll("^<|>$", "").split(";")[0])
                    .orElseGet(() -> originalInvite.getUri().toString());

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

            String host = resolveAdvertisedIp();
            int port = Optional.ofNullable(sipServer.getTransportRegistry())
                    .map(reg -> reg.resolveServerPort(originalInvite, sipServer.getConfiguration()))
                    .orElseGet(() -> (originalInvite.getTransport() == SipTransport.TCP) ? sipServer.getTcpPort() : sipServer.getUdpPort());

            String proto = Optional.ofNullable(originalInvite.getTransport()).map(Enum::name).orElse("UDP");
            String branch = "z9hG4bK-bye-" + UUID.randomUUID().toString().substring(0, 8);
            bye.getHeaders().set(SipHeaders.VIA, "SIP/2.0/" + proto + " " + host + ":" + port + ";branch=" + branch + ";rport");

            LOG.info("Sending BYE for completed announcement Call-ID: {} to {}", callId, targetUri);
            sipServer.send(bye, originalInvite.getRemoteAddress());
        } catch (Exception e) {
            LOG.warn("Failed sending BYE for completed announcement Call-ID: {}: {}", callId, e.getMessage(), e);
        }
    }

    private String resolveAdvertisedIp() {
        return Optional.ofNullable(sipServer)
                .map(SipNettyServer::getConfiguration)
                .map(SipServerConfiguration::resolveAdvertisedIp)
                .orElse("127.0.0.1");
    }

    private Optional<String> parseConnectionHost(String connectionLine) {
        return Optional.ofNullable(connectionLine)
                .map(String::trim)
                .filter(Predicate.not(String::isBlank))
                .map(line -> line.split("\\s+"))
                .filter(parts -> parts.length >= 3 && !parts[2].isBlank())
                .map(parts -> parts[2].trim());
    }

    public Map<String, AnnouncementPlayer> getActivePlayers() {
        return activePlayers;
    }

    public int getActiveCountForIp(String ip) {
        return Optional.ofNullable(activeCallsPerIp.get(ip)).map(AtomicInteger::get).orElse(0);
    }
}
