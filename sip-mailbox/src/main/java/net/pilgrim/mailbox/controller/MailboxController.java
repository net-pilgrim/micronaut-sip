package net.pilgrim.mailbox.controller;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import net.pilgrim.mailbox.config.MailboxConfiguration;
import net.pilgrim.mailbox.service.MailboxRecordingService;
import net.pilgrim.mailbox.service.MailboxSession;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.config.SipServerConfiguration;
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
        this.rtpMediaManager = Optional.ofNullable(rtpMediaManager).orElseGet(RtpMediaManager::new);
        this.recordingService = recordingService;
        this.config = Optional.ofNullable(config).orElseGet(MailboxConfiguration::new);
        this.documentLoader = Optional.ofNullable(documentLoader).orElseGet(VxmlDocumentLoader::new);
        this.audioLoader = Optional.ofNullable(audioLoader).orElseGet(DefaultVxmlAudioLoader::new);
        this.ttsClient = Optional.ofNullable(ttsClient).orElseGet(DefaultTtsClient::new);
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

        String clientIp = Optional.ofNullable(request.getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .map(InetAddress::getHostAddress)
                .orElse("127.0.0.1");

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

                    String advertisedIp = resolveAdvertisedIp();
                    String sdpAnswer = Optional.ofNullable(sdpOffer)
                            .filter(Predicate.not(String::isBlank))
                            .map(offer -> sdpNegotiator.createAnswer(offer, localAudioPort, advertisedIp))
                            .orElseGet(() -> sdpNegotiator.createOffer(localAudioPort, advertisedIp));

                    String mailboxOwner = extractMailboxOwner(request.getUri()).orElse(null);
                    Optional.ofNullable(session).ifPresent(s -> {
                        s.setState(SipSession.State.EARLY);
                        s.setAttribute("vxmlDoc", vxmlDoc);
                        s.setAttribute("sdpOffer", sdpOffer);
                        s.setAttribute("originalInvite", request);
                        s.setAttribute("mailboxOwner", mailboxOwner);
                    });

                    int serverPort = (request.getTransport() == SipTransport.TCP)
                            ? Optional.ofNullable(sipServer).map(SipNettyServer::getTcpPort).orElse(5060)
                            : Optional.ofNullable(sipServer).map(SipNettyServer::getUdpPort).orElse(5060);

                    SipResponse ok = SipResponse.ok(request, sdpAnswer, "application/sdp");
                    String contactUser = Optional.ofNullable(request.getUri())
                            .map(SipUri::getUser)
                            .orElseGet(config::getUser);
                    String contactUri = "<sip:" + contactUser + "@" + advertisedIp + ":" + serverPort
                            + (request.getTransport() == SipTransport.TCP ? ";transport=tcp" : "") + ">";
                    ok.getHeaders().setContact(contactUri);

                    Optional.ofNullable(session).ifPresent(s -> s.setAttribute("originalOk", ok));

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
        Optional.ofNullable(session).ifPresent(s -> s.setState(SipSession.State.CONFIRMED));

        Optional<RtpMediaSession> mediaSessionOpt = rtpMediaManager.findSession(callId);
        if (mediaSessionOpt.isEmpty()) {
            LOG.warn("No RTP media session found for Call-ID: {} on ACK", callId);
            return;
        }
        RtpMediaSession mediaSession = mediaSessionOpt.get();

        // Connect remote RTP address from SDP offer
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

        VxmlDocument vxmlDoc = Optional.ofNullable(session).map(s -> (VxmlDocument) s.getAttribute("vxmlDoc")).orElse(null);
        SipRequest originalInvite = Optional.ofNullable(session).map(s -> (SipRequest) s.getAttribute("originalInvite")).orElse(null);
        SipResponse originalOk = Optional.ofNullable(session).map(s -> (SipResponse) s.getAttribute("originalOk")).orElse(null);
        String mailboxOwner = Optional.ofNullable(session)
                .map(s -> (String) s.getAttribute("mailboxOwner"))
                .or(() -> Optional.ofNullable(originalInvite).map(SipRequest::getUri).flatMap(MailboxController::extractMailboxOwner))
                .or(() -> Optional.ofNullable(request.getUri()).flatMap(MailboxController::extractMailboxOwner))
                .orElse(null);

        String caller = Optional.ofNullable(originalInvite)
                .map(SipRequest::getFrom)
                .orElse("anonymous");

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

        Optional.ofNullable(vxmlDoc).ifPresentOrElse(
                doc -> mailboxSession.start(doc, documentLoader, audioLoader, ttsClient),
                () -> {
                    LOG.warn("No VoiceXML document present for Mailbox Call-ID: {}, terminating call.", callId);
                    terminateAndSendBye(mailboxSession);
                }
        );
    }

    // ==========================================
    // BYE Handler (Caller hung up)
    // ==========================================

    @OnBye("mailbox")
    public Mono<SipResponse> onMailboxBye(SipRequest request,
                                          @SipCallId String callId,
                                          SipSession session) {
        LOG.info("Received BYE for Mailbox Call-ID: {}", callId);
        Optional.ofNullable(activeSessions.remove(callId)).ifPresent(MailboxSession::onCallerHangup);
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
        return Optional.ofNullable(dtmf)
                .map(signal -> {
                    LOG.info("Received DTMF '{}' via INFO for Mailbox Call-ID: {}", signal.getDigit(), callId);
                    Optional.ofNullable(activeSessions.get(callId)).ifPresent(ms -> ms.onDtmf(signal.getDigit()));
                    SipResponse response = SipResponse.ok(request);
                    response.getHeaders().set("X-Received-DTMF", String.valueOf(signal.getDigit()));
                    return Mono.just(response);
                })
                .orElseGet(() -> Mono.just(SipResponse.ok(request)));
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
            String targetUri = Optional.ofNullable(originalInvite.getContact())
                    .filter(Predicate.not(String::isEmpty))
                    .or(() -> Optional.ofNullable(originalInvite.getFrom()))
                    .map(uri -> uri.replaceAll("^<|>$", "").split(";")[0])
                    .orElseGet(() -> originalInvite.getUri().toString());

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
        Optional.ofNullable(callIdToClientIp.remove(callId)).ifPresent(clientIp -> {
            AtomicInteger counter = activeCallsPerIp.get(clientIp);
            if (counter != null && counter.decrementAndGet() <= 0) {
                activeCallsPerIp.remove(clientIp, counter);
            }
        });
    }

    private void cleanupCall(String callId, @Nullable SipSession session) {
        releaseClientIp(callId);
        Optional.ofNullable(activeSessions.remove(callId)).ifPresent(MailboxSession::terminate);
        Optional.ofNullable(session)
                .ifPresentOrElse(
                        s -> s.setState(SipSession.State.TERMINATED),
                        () -> Optional.ofNullable(sessionManager)
                                .flatMap(sm -> sm.findSession(callId))
                                .ifPresent(s -> s.setState(SipSession.State.TERMINATED))
                );
        rtpMediaManager.terminateSession(callId);
    }

    private String resolveAdvertisedIp() {
        return Optional.ofNullable(config.getAdvertisedIp())
                .map(String::trim)
                .filter(Predicate.not(String::isBlank))
                .or(() -> Optional.ofNullable(sipServer)
                        .map(SipNettyServer::getConfiguration)
                        .map(SipServerConfiguration::resolveAdvertisedIp))
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

    public static Optional<String> extractMailboxOwner(SipUri uri) {
        return Optional.ofNullable(uri)
                .map(SipUri::getUser)
                .map(String::trim)
                .filter(Predicate.not(String::isBlank))
                .flatMap(user -> {
                    int plusIdx = user.indexOf('+');
                    if (plusIdx > 0 && user.substring(plusIdx + 1).equalsIgnoreCase("mailbox")) {
                        return Optional.of(user.substring(0, plusIdx));
                    }
                    return Optional.empty();
                });
    }

    public Map<String, MailboxSession> getActiveSessions() {
        return activeSessions;
    }

    public Optional<MailboxSession> findSession(String callId) {
        return Optional.ofNullable(activeSessions.get(callId));
    }
}
