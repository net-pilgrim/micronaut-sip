package net.pilgrim.netann.controller;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import net.pilgrim.netann.config.NetannConfiguration;
import net.pilgrim.netann.service.AnnouncementAudioLoader;
import net.pilgrim.vxml.ast.VxmlDocument;
import net.pilgrim.vxml.loader.VxmlDocumentLoader;
import net.pilgrim.netann.vxml.media.RtpVxmlMedia;
import net.pilgrim.netann.vxml.media.RtpVxmlMediaFactory;
import net.pilgrim.netann.vxml.media.VxmlMediaFactory;
import net.pilgrim.netann.vxml.runtime.VxmlAudioPlayer;
import net.pilgrim.vxml.media.VxmlMedia;
import net.pilgrim.vxml.runtime.VxmlOutputSink;
import net.pilgrim.vxml.runtime.VxmlSession;
import net.pilgrim.vxml.speech.DefaultTtsClient;
import net.pilgrim.vxml.speech.TtsClient;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.dtmf.DtmfSignal;
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
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controller implementing RFC 4240 §4 Dialog Service and RFC 5552 VoiceXML Media Server interface.
 * Gated behind {@code netann.vxml.enabled} configuration property to ensure existing RFC 4240
 * announcement behavior remains stable and isolated.
 */
@Requires(property = "netann.vxml.enabled", notEquals = "false", defaultValue = "true")
@SipController
public class VxmlController {

    private static final Logger LOG = LoggerFactory.getLogger(VxmlController.class);

    private final RtpMediaManager rtpMediaManager;
    private final VxmlDocumentLoader documentLoader;
    private final AnnouncementAudioLoader audioLoader;
    private final TtsClient ttsClient;
    private final SipNettyServer sipServer;
    private final SipSessionManager sessionManager;
    private final NetannConfiguration config;
    private final VxmlMediaFactory mediaFactory;
    private final SdpNegotiator sdpNegotiator = new SdpNegotiator();
    private final SdpParser sdpParser = new SdpParser();

    private final Map<String, VxmlSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, VxmlAudioPlayer> activePlayers = new ConcurrentHashMap<>();
    private final Map<String, String> callIdToClientIp = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> activeCallsPerIp = new ConcurrentHashMap<>();

    @Inject
    public VxmlController(RtpMediaManager rtpMediaManager,
                          VxmlDocumentLoader documentLoader,
                          AnnouncementAudioLoader audioLoader,
                          @Nullable TtsClient ttsClient,
                          SipNettyServer sipServer,
                          SipSessionManager sessionManager,
                          @Nullable NetannConfiguration config,
                          @Nullable VxmlMediaFactory mediaFactory) {
        this.rtpMediaManager = rtpMediaManager != null ? rtpMediaManager : new RtpMediaManager();
        this.documentLoader = documentLoader != null ? documentLoader : new VxmlDocumentLoader();
        this.audioLoader = audioLoader != null ? audioLoader : new AnnouncementAudioLoader();
        this.ttsClient = ttsClient != null ? ttsClient : new DefaultTtsClient();
        this.sipServer = sipServer;
        this.sessionManager = sessionManager;
        this.config = config != null ? config : new NetannConfiguration();
        this.mediaFactory = mediaFactory != null ? mediaFactory : new RtpVxmlMediaFactory();
    }

    // ==========================================
    // INVITE Handlers for dialog and vxml URIs
    // ==========================================

    @OnInvite("dialog")
    public Mono<SipResponse> onDialogInvite(SipRequest request,
                                            @SipCallId String callId,
                                            @SipBody String sdpOffer,
                                            SipSession session) {
        return handleInvite(request, callId, sdpOffer, session, "dialog");
    }

    @OnInvite("vxml")
    public Mono<SipResponse> onVxmlInvite(SipRequest request,
                                          @SipCallId String callId,
                                          @SipBody String sdpOffer,
                                          SipSession session) {
        return handleInvite(request, callId, sdpOffer, session, "vxml");
    }

    private Mono<SipResponse> handleInvite(SipRequest request,
                                           String callId,
                                           String sdpOffer,
                                           SipSession session,
                                           String serviceName) {
        LOG.info("Received RFC 4240/5552 {} INVITE for Call-ID: {} (URI: {})", serviceName, callId, request.getUri());

        // Concurrency check
        if (activeSessions.size() >= config.getMaxActiveAnnouncements()) {
            LOG.warn("Max active announcements limit reached ({}), rejecting VXML Call-ID: {}",
                    config.getMaxActiveAnnouncements(), callId);
            SipResponse overloaded = request.createResponse(503, "Service Unavailable");
            overloaded.getHeaders().set("Retry-After", "10");
            return Mono.just(overloaded);
        }

        String clientIp = (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null)
                ? request.getRemoteAddress().getAddress().getHostAddress() : "127.0.0.1";

        AtomicInteger ipCounter = activeCallsPerIp.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        if (ipCounter.incrementAndGet() > config.getMaxAnnouncementsPerIp()) {
            ipCounter.decrementAndGet();
            LOG.warn("Max announcements per IP limit reached for {} ({}), rejecting VXML Call-ID: {}",
                    clientIp, config.getMaxAnnouncementsPerIp(), callId);
            SipResponse overloaded = request.createResponse(503, "Service Unavailable");
            overloaded.getHeaders().set("Retry-After", "10");
            return Mono.just(overloaded);
        }
        callIdToClientIp.put(callId, clientIp);

        // Resolve VoiceXML document reference
        String vxmlUri = resolveVxmlUri(request);
        if (vxmlUri == null || vxmlUri.isBlank()) {
            releaseClientIp(callId);
            LOG.warn("Missing mandatory voicexml parameter for Call-ID: {}", callId);
            return Mono.just(request.createResponse(SipStatus.BAD_REQUEST, "Mandatory voicexml parameter missing"));
        }

        // Asynchronously load and parse VoiceXML document off the event loop
        return Mono.fromCallable(() -> documentLoader.loadDocument(vxmlUri))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(vxmlDoc -> {
                    int localAudioPort = 49170;
                    try {
                        RtpMediaSession mediaSession = rtpMediaManager.createSession(callId);
                        localAudioPort = mediaSession.getLocalPort();
                    } catch (Exception e) {
                        LOG.warn("Failed creating RTP media session for Call-ID: {}", callId, e);
                    }

                    String advertisedIp = resolveAdvertisedIp();
                    String sdpAnswer = (sdpOffer != null && !sdpOffer.isBlank())
                            ? sdpNegotiator.createAnswer(sdpOffer, localAudioPort, advertisedIp)
                            : sdpNegotiator.createOffer(localAudioPort, advertisedIp);

                    session.setState(SipSession.State.EARLY);
                    session.setAttribute("vxmlDoc", vxmlDoc);
                    session.setAttribute("vxmlUri", vxmlUri);
                    session.setAttribute("sdpOffer", sdpOffer);
                    session.setAttribute("originalInvite", request);

                    int serverPort = (request.getTransport() == SipTransport.TCP)
                            ? (sipServer != null ? sipServer.getTcpPort() : 5060)
                            : (sipServer != null ? sipServer.getUdpPort() : 5060);

                    SipResponse ok = SipResponse.ok(request, sdpAnswer, "application/sdp");
                    String contactUri = "<sip:" + serviceName + "@" + advertisedIp + ":" + serverPort
                            + (request.getTransport() == SipTransport.TCP ? ";transport=tcp" : "") + ">";
                    ok.getHeaders().setContact(contactUri);
                    session.setAttribute("originalOk", ok);

                    return Mono.just(ok);
                })
                .onErrorResume(FileNotFoundException.class, e -> {
                    releaseClientIp(callId);
                    LOG.warn("VoiceXML document not found for '{}' (Call-ID: {})", vxmlUri, callId);
                    return Mono.just(request.createResponse(SipStatus.NOT_FOUND, "VoiceXML document not found"));
                })
                .onErrorResume(SecurityException.class, e -> {
                    releaseClientIp(callId);
                    LOG.warn("Security violation loading VoiceXML '{}' (Call-ID: {}): {}", vxmlUri, callId, e.getMessage());
                    return Mono.just(request.createResponse(SipStatus.FORBIDDEN, "Forbidden: " + e.getMessage()));
                })
                .onErrorResume(Exception.class, e -> {
                    releaseClientIp(callId);
                    LOG.warn("Invalid VoiceXML document for '{}' (Call-ID: {}): {}", vxmlUri, callId, e.getMessage(), e);
                    return Mono.just(request.createResponse(SipStatus.BAD_REQUEST, "Invalid VoiceXML document"));
                });
    }

    private String resolveVxmlUri(SipRequest request) {
        if (request.getUri() != null) {
            String param = request.getUri().getParameter("voicexml");
            if (param != null && !param.isBlank()) {
                return param.trim();
            }
        }
        // Fallback: check if SIP body contains inline VoiceXML document
        String contentType = request.getContentType();
        String body = request.getBodyAsString();
        if (body != null && !body.isBlank()) {
            if ("application/voicexml+xml".equalsIgnoreCase(contentType)
                    || "text/xml".equalsIgnoreCase(contentType)
                    || body.trim().startsWith("<vxml")
                    || body.trim().startsWith("<?xml")) {
                return body.trim();
            }
        }
        return null;
    }

    // ==========================================
    // ACK Handlers
    // ==========================================

    @OnAck("dialog")
    public void onDialogAck(SipRequest request,
                            @SipCallId String callId,
                            SipSession session) {
        handleAck(request, callId, session);
    }

    @OnAck("vxml")
    public void onVxmlAck(SipRequest request,
                          @SipCallId String callId,
                          SipSession session) {
        handleAck(request, callId, session);
    }

    private void handleAck(SipRequest request, String callId, SipSession session) {
        LOG.info("Received ACK for VXML Call-ID: {}, starting interpreter session.", callId);
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
                    LOG.info("Configured remote RTP destination {}:{} for VXML Call-ID: {}", host, audio.getPort(), callId);
                }
            } catch (Exception e) {
                LOG.warn("Failed parsing SDP offer for Call-ID: {}", callId, e);
            }
        }

        VxmlDocument vxmlDoc = (session != null) ? session.getAttribute("vxmlDoc") : null;
        String vxmlUri = (session != null) ? session.getAttribute("vxmlUri") : null;
        SipRequest originalInvite = (session != null) ? session.getAttribute("originalInvite") : null;
        SipResponse originalOk = (session != null) ? session.getAttribute("originalOk") : null;

        if (vxmlDoc == null) {
            LOG.warn("No VoiceXML document present for Call-ID: {}, terminating call.", callId);
            terminateAndSendBye(callId, session, originalInvite, originalOk);
            return;
        }

        VxmlMedia media = mediaFactory.createMedia(callId, mediaSession);

        // Maintain activePlayers mapping for telemetry/testing compatibility
        VxmlAudioPlayer playerAdapter = new VxmlAudioPlayer(mediaSession, new byte[0], null) {
            @Override
            public boolean isRunning() {
                return media.isAudioPlaying();
            }

            @Override
            public void stop() {
                media.stopAudio();
            }
        };
        activePlayers.put(callId, playerAdapter);

        VxmlSession vxmlSession = new VxmlSession(
                callId,
                vxmlDoc,
                vxmlUri,
                documentLoader,
                audioLoader,
                ttsClient,
                media,
                () -> {
                    LOG.info("VoiceXML dialog finished for Call-ID: {}. Terminating call with in-dialog BYE.", callId);
                    terminateAndSendBye(callId, session, originalInvite, originalOk);
                }
        );

        activeSessions.put(callId, vxmlSession);
        vxmlSession.start();
    }

    // ==========================================
    // INFO Handlers (DTMF Relay - RFC 6086)
    // ==========================================

    @OnInfo("dialog")
    public Mono<SipResponse> onDialogInfo(SipRequest request,
                                          @SipCallId String callId,
                                          @Nullable @SipDtmf DtmfSignal dtmf,
                                          SipSession session) {
        return handleInfo(request, callId, dtmf);
    }

    @OnInfo("vxml")
    public Mono<SipResponse> onVxmlInfo(SipRequest request,
                                        @SipCallId String callId,
                                        @Nullable @SipDtmf DtmfSignal dtmf,
                                        SipSession session) {
        return handleInfo(request, callId, dtmf);
    }

    private Mono<SipResponse> handleInfo(SipRequest request, String callId, DtmfSignal dtmf) {
        if (dtmf != null) {
            LOG.info("Received DTMF '{}' via INFO for VXML Call-ID: {}", dtmf.getDigit(), callId);
            VxmlSession vxmlSession = activeSessions.get(callId);
            if (vxmlSession != null) {
                vxmlSession.onDtmf(dtmf.getDigit());
            }
            SipResponse response = SipResponse.ok(request);
            response.getHeaders().set("X-Received-DTMF", String.valueOf(dtmf.getDigit()));
            return Mono.just(response);
        }
        return Mono.just(SipResponse.ok(request));
    }

    // ==========================================
    // BYE Handlers (Caller early hangup)
    // ==========================================

    @OnBye("dialog")
    public Mono<SipResponse> onDialogBye(SipRequest request,
                                         @SipCallId String callId,
                                         SipSession session) {
        return handleBye(request, callId, session);
    }

    @OnBye("vxml")
    public Mono<SipResponse> onVxmlBye(SipRequest request,
                                       @SipCallId String callId,
                                       SipSession session) {
        return handleBye(request, callId, session);
    }

    private Mono<SipResponse> handleBye(SipRequest request, String callId, SipSession session) {
        LOG.info("Received BYE for VXML Call-ID: {}, terminating session.", callId);
        cleanupCall(callId, session);
        return Mono.just(SipResponse.ok(request));
    }

    // ==========================================
    // CANCEL Handlers
    // ==========================================

    @OnCancel("dialog")
    public void onDialogCancel(SipRequest request,
                               @SipCallId String callId,
                               SipSession session) {
        cleanupCall(callId, session);
    }

    @OnCancel("vxml")
    public void onVxmlCancel(SipRequest request,
                             @SipCallId String callId,
                             SipSession session) {
        cleanupCall(callId, session);
    }

    // ==========================================
    // OPTIONS Handlers
    // ==========================================

    @OnOptions("dialog")
    public SipResponse onDialogOptions(SipRequest request) {
        return handleOptions(request);
    }

    @OnOptions("vxml")
    public SipResponse onVxmlOptions(SipRequest request) {
        return handleOptions(request);
    }

    private SipResponse handleOptions(SipRequest request) {
        SipResponse response = SipResponse.ok(request);
        response.getHeaders().set(SipHeaders.ALLOW, "INVITE, ACK, BYE, CANCEL, OPTIONS, INFO");
        response.getHeaders().set(SipHeaders.SUPPORTED, "100rel");
        return response;
    }

    private void releaseClientIp(String callId) {
        String clientIp = callIdToClientIp.remove(callId);
        if (clientIp != null) {
            AtomicInteger counter = activeCallsPerIp.get(clientIp);
            if (counter != null && counter.decrementAndGet() <= 0) {
                activeCallsPerIp.remove(clientIp, counter);
            }
        }
    }

    private void cleanupCall(String callId, SipSession session) {
        releaseClientIp(callId);
        VxmlAudioPlayer player = activePlayers.remove(callId);
        if (player != null) {
            player.stop();
        }
        VxmlSession vxmlSession = activeSessions.remove(callId);
        if (vxmlSession != null) {
            vxmlSession.terminate();
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

            bye.getHeaders().setTo(originalInvite.getFrom());
            bye.getHeaders().setFrom(originalOk.getTo());
            bye.getHeaders().setCallId(callId);
            bye.getHeaders().setCSeq("2 BYE");
            bye.getHeaders().setMaxForwards(70);
            bye.getHeaders().setContentLength(0);

            String host = resolveAdvertisedIp();
            int port = (originalInvite.getTransport() == SipTransport.TCP) ? sipServer.getTcpPort() : sipServer.getUdpPort();
            String proto = (originalInvite.getTransport() == SipTransport.TCP) ? "TCP" : "UDP";
            String branch = "z9hG4bK-bye-" + UUID.randomUUID().toString().substring(0, 8);
            bye.getHeaders().set(SipHeaders.VIA, "SIP/2.0/" + proto + " " + host + ":" + port + ";branch=" + branch + ";rport");

            LOG.info("Sending in-dialog BYE for completed VXML Call-ID: {} to {}", callId, targetUri);
            sipServer.send(bye, originalInvite.getRemoteAddress());
        } catch (Exception e) {
            LOG.warn("Failed sending BYE for completed VXML Call-ID: {}: {}", callId, e.getMessage(), e);
        }
    }

    private String resolveAdvertisedIp() {
        if (sipServer != null && sipServer.getConfiguration() != null) {
            return sipServer.getConfiguration().resolveAdvertisedIp();
        }
        return "127.0.0.1";
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

    public Map<String, VxmlSession> getActiveSessions() {
        return activeSessions;
    }

    public Map<String, VxmlAudioPlayer> getActivePlayers() {
        return activePlayers;
    }

    public VxmlMediaFactory getMediaFactory() {
        return mediaFactory;
    }
}
