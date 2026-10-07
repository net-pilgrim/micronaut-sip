package net.pilgrim.mailbox.controller;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import net.pilgrim.mailbox.config.MailboxConfiguration;
import net.pilgrim.mailbox.service.MailboxRecordingService;
import net.pilgrim.mailbox.service.MailboxSession;
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
import net.pilgrim.vxml.ast.VxmlDocument;
import net.pilgrim.vxml.loader.DefaultVxmlAudioLoader;
import net.pilgrim.vxml.loader.VxmlAudioLoader;
import net.pilgrim.vxml.loader.VxmlDocumentLoader;
import net.pilgrim.vxml.speech.DefaultTtsClient;
import net.pilgrim.vxml.speech.TtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SIP Controller handling calls to {@code mailbox@<sip-ip>}.
 * Executes answering machine dialog logic driven entirely by VoiceXML.
 */
@Requires(property = "mailbox.enabled", notEquals = "false", defaultValue = "true")
@SipController
public class MailboxController {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxController.class);

    private final RtpMediaManager rtpMediaManager;
    private final MailboxRecordingService recordingService;
    private final MailboxConfiguration config;
    private final VxmlDocumentLoader documentLoader;
    private final VxmlAudioLoader audioLoader;
    private final TtsClient ttsClient;
    private final SipNettyServer sipServer;
    private final SipSessionManager sessionManager;
    private final SdpNegotiator sdpNegotiator = new SdpNegotiator();
    private final SdpParser sdpParser = new SdpParser();

    private final Map<String, MailboxSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, String> callIdToClientIp = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> activeCallsPerIp = new ConcurrentHashMap<>();

    @Inject
    public MailboxController(RtpMediaManager rtpMediaManager,
                             MailboxRecordingService recordingService,
                             MailboxConfiguration config,
                             @Nullable VxmlDocumentLoader documentLoader,
                             @Nullable VxmlAudioLoader audioLoader,
                             @Nullable TtsClient ttsClient,
                             @Nullable SipNettyServer sipServer,
                             @Nullable SipSessionManager sessionManager) {
        this.rtpMediaManager = rtpMediaManager != null ? rtpMediaManager : new RtpMediaManager();
        this.recordingService = recordingService;
        this.config = config != null ? config : new MailboxConfiguration();
        this.documentLoader = documentLoader != null ? documentLoader : new VxmlDocumentLoader();
        this.audioLoader = audioLoader != null ? audioLoader : new DefaultVxmlAudioLoader();
        this.ttsClient = ttsClient != null ? ttsClient : new DefaultTtsClient();
        this.sipServer = sipServer;
        this.sessionManager = sessionManager;
    }

    // ==========================================
    // INVITE Handler for mailbox@<sip-ip>
    // ==========================================

    @OnInvite("mailbox")
    public Mono<SipResponse> onMailboxInvite(SipRequest request,
                                             @SipCallId String callId,
                                             @SipBody String sdpOffer,
                                             SipSession session) {
        LOG.info("Received Answering Machine INVITE for Call-ID: {} from {}", callId, request.getFrom());

        // Concurrency checks
        if (activeSessions.size() >= config.getMaxActiveCalls()) {
            LOG.warn("Max active mailbox calls reached ({}), rejecting Call-ID: {}", config.getMaxActiveCalls(), callId);
            SipResponse overloaded = request.createResponse(503, "Service Unavailable");
            overloaded.getHeaders().set("Retry-After", "10");
            return Mono.just(overloaded);
        }

        String clientIp = (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null)
                ? request.getRemoteAddress().getAddress().getHostAddress() : "127.0.0.1";

        AtomicInteger ipCounter = activeCallsPerIp.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        if (ipCounter.incrementAndGet() > config.getMaxCallsPerIp()) {
            ipCounter.decrementAndGet();
            LOG.warn("Max mailbox calls per IP reached for {} ({}), rejecting Call-ID: {}",
                    clientIp, config.getMaxCallsPerIp(), callId);
            SipResponse overloaded = request.createResponse(503, "Service Unavailable");
            overloaded.getHeaders().set("Retry-After", "10");
            return Mono.just(overloaded);
        }
        callIdToClientIp.put(callId, clientIp);

        return Mono.fromCallable(() -> documentLoader.loadDocument(config.getPromptVxml()))
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

                    String mailboxOwner = extractMailboxOwner(request.getUri()).orElse(null);
                    if (session != null) {
                        session.setState(SipSession.State.EARLY);
                        session.setAttribute("vxmlDoc", vxmlDoc);
                        session.setAttribute("sdpOffer", sdpOffer);
                        session.setAttribute("originalInvite", request);
                        session.setAttribute("mailboxOwner", mailboxOwner);
                    }

                    int serverPort = (request.getTransport() == SipTransport.TCP)
                            ? (sipServer != null ? sipServer.getTcpPort() : 5060)
                            : (sipServer != null ? sipServer.getUdpPort() : 5060);

                    SipResponse ok = SipResponse.ok(request, sdpAnswer, "application/sdp");
                    String contactUser = (request.getUri() != null && request.getUri().getUser() != null)
                            ? request.getUri().getUser() : config.getUser();
                    String contactUri = "<sip:" + contactUser + "@" + advertisedIp + ":" + serverPort
                            + (request.getTransport() == SipTransport.TCP ? ";transport=tcp" : "") + ">";
                    ok.getHeaders().setContact(contactUri);

                    if (session != null) {
                        session.setAttribute("originalOk", ok);
                    }

                    return Mono.just(ok);
                })
                .onErrorResume(Exception.class, e -> {
                    releaseClientIp(callId);
                    LOG.error("Failed preparing mailbox session for Call-ID: {}", callId, e);
                    return Mono.just(request.createResponse(SipStatus.SERVER_INTERNAL_ERROR, "Internal Error"));
                });
    }

    // ==========================================
    // ACK Handler
    // ==========================================

    @OnAck("mailbox")
    public void onMailboxAck(SipRequest request,
                             @SipCallId String callId,
                             SipSession session) {
        LOG.info("Received ACK for Mailbox Call-ID: {}, starting answering machine dialog.", callId);
        if (session != null) {
            session.setState(SipSession.State.CONFIRMED);
        }

        Optional<RtpMediaSession> mediaSessionOpt = rtpMediaManager.findSession(callId);
        if (mediaSessionOpt.isEmpty()) {
            LOG.warn("No RTP media session found for Call-ID: {} on ACK", callId);
            return;
        }
        RtpMediaSession mediaSession = mediaSessionOpt.get();

        // Connect remote RTP address from SDP offer
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

        VxmlDocument vxmlDoc = (session != null) ? session.getAttribute("vxmlDoc") : null;
        SipRequest originalInvite = (session != null) ? session.getAttribute("originalInvite") : null;
        SipResponse originalOk = (session != null) ? session.getAttribute("originalOk") : null;
        String mailboxOwner = (session != null) ? session.getAttribute("mailboxOwner") : null;
        if (mailboxOwner == null && originalInvite != null) {
            mailboxOwner = extractMailboxOwner(originalInvite.getUri()).orElse(null);
        }
        if (mailboxOwner == null) {
            mailboxOwner = extractMailboxOwner(request.getUri()).orElse(null);
        }

        String caller = (originalInvite != null && originalInvite.getFrom() != null)
                ? originalInvite.getFrom() : "anonymous";

        MailboxSession mailboxSession = new MailboxSession(
                callId,
                mailboxOwner,
                caller,
                mediaSession,
                recordingService,
                config,
                session,
                originalInvite,
                originalOk,
                this::terminateAndSendBye
        );

        activeSessions.put(callId, mailboxSession);

        if (vxmlDoc != null) {
            mailboxSession.start(vxmlDoc, documentLoader, audioLoader, ttsClient);
        } else {
            LOG.warn("No VoiceXML document present for Mailbox Call-ID: {}, terminating call.", callId);
            terminateAndSendBye(mailboxSession);
        }
    }

    // ==========================================
    // BYE Handler (Caller hung up)
    // ==========================================

    @OnBye("mailbox")
    public Mono<SipResponse> onMailboxBye(SipRequest request,
                                          @SipCallId String callId,
                                          SipSession session) {
        LOG.info("Received BYE for Mailbox Call-ID: {}", callId);
        MailboxSession mailboxSession = activeSessions.remove(callId);
        if (mailboxSession != null) {
            mailboxSession.onCallerHangup();
        }
        cleanupCall(callId, session);
        return Mono.just(SipResponse.ok(request));
    }

    // ==========================================
    // INFO Handler (DTMF relay)
    // ==========================================

    @OnInfo("mailbox")
    public Mono<SipResponse> onMailboxInfo(SipRequest request,
                                           @SipCallId String callId,
                                           @Nullable @SipDtmf DtmfSignal dtmf,
                                           SipSession session) {
        if (dtmf != null) {
            LOG.info("Received DTMF '{}' via INFO for Mailbox Call-ID: {}", dtmf.getDigit(), callId);
            MailboxSession mailboxSession = activeSessions.get(callId);
            if (mailboxSession != null) {
                mailboxSession.onDtmf(dtmf.getDigit());
            }
            SipResponse response = SipResponse.ok(request);
            response.getHeaders().set("X-Received-DTMF", String.valueOf(dtmf.getDigit()));
            return Mono.just(response);
        }
        return Mono.just(SipResponse.ok(request));
    }

    // ==========================================
    // CANCEL Handler
    // ==========================================

    @OnCancel("mailbox")
    public void onMailboxCancel(SipRequest request,
                                @SipCallId String callId,
                                SipSession session) {
        LOG.info("Received CANCEL for Mailbox Call-ID: {}", callId);
        cleanupCall(callId, session);
    }

    // ==========================================
    // OPTIONS Handler
    // ==========================================

    @OnOptions("mailbox")
    public SipResponse onMailboxOptions(SipRequest request) {
        SipResponse response = SipResponse.ok(request);
        response.getHeaders().set(SipHeaders.ALLOW, "INVITE, ACK, BYE, CANCEL, OPTIONS, INFO");
        response.getHeaders().set(SipHeaders.SUPPORTED, "100rel");
        return response;
    }

    // ==========================================
    // Helper & Termination Methods
    // ==========================================

    private void terminateAndSendBye(MailboxSession mailboxSession) {
        String callId = mailboxSession.getCallId();
        activeSessions.remove(callId);
        cleanupCall(callId, null);

        SipRequest originalInvite = mailboxSession.getOriginalInvite();
        SipResponse originalOk = mailboxSession.getOriginalOk();

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

            LOG.info("Sending in-dialog BYE for completed Mailbox Call-ID: {} to {}", callId, targetUri);
            sipServer.send(bye, originalInvite.getRemoteAddress());
        } catch (Exception e) {
            LOG.warn("Failed sending BYE for completed Mailbox Call-ID: {}: {}", callId, e.getMessage(), e);
        }
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

    private void cleanupCall(String callId, @Nullable SipSession session) {
        releaseClientIp(callId);
        MailboxSession mailboxSession = activeSessions.remove(callId);
        if (mailboxSession != null) {
            mailboxSession.terminate();
        }
        if (session != null) {
            session.setState(SipSession.State.TERMINATED);
        } else if (sessionManager != null) {
            sessionManager.findSession(callId).ifPresent(s -> s.setState(SipSession.State.TERMINATED));
        }
        rtpMediaManager.terminateSession(callId);
    }

    private String resolveAdvertisedIp() {
        if (config.getAdvertisedIp() != null && !config.getAdvertisedIp().isBlank()) {
            return config.getAdvertisedIp().trim();
        }
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

    public static Optional<String> extractMailboxOwner(SipUri uri) {
        if (uri == null || uri.getUser() == null || uri.getUser().isBlank()) {
            return Optional.empty();
        }
        String user = uri.getUser().trim();
        int plusIdx = user.indexOf('+');
        if (plusIdx > 0 && user.substring(plusIdx + 1).equalsIgnoreCase("mailbox")) {
            return Optional.of(user.substring(0, plusIdx));
        }
        return Optional.empty();
    }

    public Map<String, MailboxSession> getActiveSessions() {
        return activeSessions;
    }

    public Optional<MailboxSession> findSession(String callId) {
        return Optional.ofNullable(activeSessions.get(callId));
    }
}
