package net.pilgrim.controller;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.mrcp.message.*;
import net.pilgrim.mrcp.mistral.MistralAudioClient;
import net.pilgrim.mrcp.resource.*;
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Interactive voice assistant controller demonstrating MRCPv2 speech synthesis (TTS)
 * and speech recognition (ASR) powered by Mistral Audio models over live SIP/RTP calls.
 * <p>
 * Dialable via {@code sip:ai@...} or {@code sip:mrcp@...}.
 */
@SipController
@Singleton
public class MrcpVoiceAssistantController {

    private static final Logger LOG = LoggerFactory.getLogger(MrcpVoiceAssistantController.class);

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "mrcp-assistant-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final RtpMediaManager rtpMediaManager;
    private final MistralAudioClient mistralClient;
    private final MrcpChannelManager channelManager;
    private final SipNettyServer sipServer;
    private final SipSessionManager sessionManager;

    private final SdpNegotiator sdpNegotiator = new SdpNegotiator();
    private final SdpParser sdpParser = new SdpParser();

    private final Map<String, SpeechSynthesizerResource> activeSynths = new ConcurrentHashMap<>();
    private final Map<String, SpeechRecognizerResource> activeRecogs = new ConcurrentHashMap<>();

    @Inject
    public MrcpVoiceAssistantController(RtpMediaManager rtpMediaManager,
                                        MistralAudioClient mistralClient,
                                        MrcpChannelManager channelManager,
                                        @Nullable SipNettyServer sipServer,
                                        @Nullable SipSessionManager sessionManager,
                                        @Nullable net.pilgrim.mrcp.server.MrcpServer mrcpServer) {
        this.rtpMediaManager = Optional.ofNullable(rtpMediaManager).orElseGet(RtpMediaManager::new);
        this.mistralClient = mistralClient;
        this.channelManager = channelManager;
        this.sipServer = sipServer;
        this.sessionManager = sessionManager;
    }

    @OnInvite("mrcp")
    public Mono<SipResponse> onMrcpInvite(SipRequest request,
                                          @SipCallId String callId,
                                          @SipBody String sdpOffer,
                                          SipSession session) {
        return handleAiInvite(request, callId, sdpOffer, session, "mrcp");
    }

    @OnInvite("ai")
    public Mono<SipResponse> onAiInvite(SipRequest request,
                                        @SipCallId String callId,
                                        @SipBody String sdpOffer,
                                        SipSession session) {
        return handleAiInvite(request, callId, sdpOffer, session, "ai");
    }

    private Mono<SipResponse> handleAiInvite(SipRequest request,
                                            String callId,
                                            String sdpOffer,
                                            SipSession session,
                                            String userPart) {
        LOG.info("Received INVITE for AI Voice Assistant ({}) Call-ID: {}", userPart, callId);

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

        Optional.ofNullable(session).ifPresent(s -> {
            s.handleInvite(request, sdpOffer);
            s.setState(SipSession.State.EARLY);
            s.setAttribute("sdpOffer", sdpOffer);
            s.setAttribute("originalInvite", request);
            s.setAttribute("userPart", userPart);
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
                .map(reg -> reg.formatContactUri(request, advertisedIp, serverPort, userPart))
                .orElseGet(() -> {
                    boolean isTls = request.getTransport() == SipTransport.TLS;
                    String scheme = isTls ? "sips" : "sip";
                    String transportParam = Optional.ofNullable(request.getTransport())
                            .filter(t -> t != SipTransport.UDP)
                            .map(t -> ";transport=" + t.name().toLowerCase())
                            .orElse("");
                    return "<" + scheme + ":" + userPart + "@" + advertisedIp + ":" + serverPort + transportParam + ">";
                });
        ok.getHeaders().setContact(contactUri);
        Optional.ofNullable(session).ifPresent(s -> s.setAttribute("originalOk", ok));

        return Mono.just(ok);
    }

    @OnAck("mrcp")
    public void onMrcpAck(SipRequest request, @SipCallId String callId, SipSession session) {
        startAssistantFlow(request, callId, session);
    }

    @OnAck("ai")
    public void onAiAck(SipRequest request, @SipCallId String callId, SipSession session) {
        startAssistantFlow(request, callId, session);
    }

    private void startAssistantFlow(SipRequest request, String callId, SipSession session) {
        LOG.info("Received ACK for AI Voice Assistant Call-ID: {}, configuring media and starting MRCP dialog.", callId);
        Optional.ofNullable(session).ifPresent(s -> s.handleAck(request, null));

        Optional<RtpMediaSession> mediaSessionOpt = rtpMediaManager.findSession(callId);
        if (mediaSessionOpt.isEmpty()) {
            LOG.warn("No RTP media session found for Call-ID: {} on ACK", callId);
            return;
        }
        RtpMediaSession mediaSession = mediaSessionOpt.get();

        // Connect remote RTP port from SDP offer
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
                                    LOG.info("Configured remote RTP destination {}:{} for AI Assistant Call-ID: {}", host, audio.getPort(), callId);
                                });
                    } catch (Exception e) {
                        LOG.warn("Failed parsing SDP offer for Call-ID: {}", callId, e);
                    }
                });

        // 1. Create MRCP synthesizer and recognizer channels for this call
        MrcpChannelId synthId = new MrcpChannelId(callId, MrcpResourceType.SPEECHSYNTH);
        SpeechSynthesizerResource synth = new SpeechSynthesizerResource(synthId, mistralClient, mediaSession);
        activeSynths.put(callId, synth);
        channelManager.registerResource(synth);

        MrcpChannelId recogId = new MrcpChannelId(callId, MrcpResourceType.SPEECHRECOG);
        SpeechRecognizerResource recog = new SpeechRecognizerResource(recogId, mistralClient, mediaSession);
        activeRecogs.put(callId, recog);
        channelManager.registerResource(recog);

        // 2. Play Welcome Prompt via Mistral TTS over MRCP
        String welcomePrompt = "Welcome to the Mistral AI voice assistant. How may I help you today?";
        MrcpRequest speakReq = new MrcpRequest(MrcpMethod.SPEAK, 1);
        speakReq.getHeaders().setChannelIdentifier(synthId);
        speakReq.setBody(welcomePrompt, "text/plain");

        synth.handleRequest(speakReq, event -> {
            if (event.getEvent() == MrcpEventName.SPEAK_COMPLETE) {
                LOG.info("Welcome prompt completed for Call-ID: {}, starting speech recognition", callId);
                startListening(callId, recog, synth, session);
            }
        }).subscribe();
    }

    private void startListening(String callId,
                                SpeechRecognizerResource recog,
                                SpeechSynthesizerResource synth,
                                SipSession session) {
        // Start MRCP recognition
        MrcpRequest recogReq = new MrcpRequest(MrcpMethod.RECOGNIZE, 2);
        recogReq.getHeaders().setChannelIdentifier(recog.getChannelId());
        recogReq.setBody("grammar: support | sales | billing | help", "text/plain");

        recog.handleRequest(recogReq, event -> {
            if (event.getEvent() == MrcpEventName.START_OF_INPUT) {
                LOG.info("Caller started speaking on Call-ID: {}", callId);
            } else if (event.getEvent() == MrcpEventName.RECOGNITION_COMPLETE) {
                String resultText = recog.getLastResult() != null ? recog.getLastResult().getText() : "support";
                LOG.info("Speech recognition complete on Call-ID: {}: '{}'", callId, resultText);
                playGoodbyeAndHangup(callId, synth, resultText, session);
            }
        }).subscribe();

        // Feed sample speech or listen for 3 seconds then conclude recognition
        SCHEDULER.schedule(() -> {
            recog.feedAudioBytes(new byte[3200]); // simulate incoming speech frame
            recog.finishRecognition().subscribe(
                    null,
                    e -> LOG.debug("Finished recognition on Call-ID {}: {}", callId, e.getMessage())
            );
        }, 2, TimeUnit.SECONDS);
    }

    private void playGoodbyeAndHangup(String callId,
                                      SpeechSynthesizerResource synth,
                                      String recognizedText,
                                      SipSession session) {
        String answer = "I heard you say: " + recognizedText + ". Thank you for testing Mistral AI voice services. Goodbye!";
        MrcpRequest byeSpeak = new MrcpRequest(MrcpMethod.SPEAK, 3);
        byeSpeak.getHeaders().setChannelIdentifier(synth.getChannelId());
        byeSpeak.setBody(answer, "text/plain");

        synth.handleRequest(byeSpeak, event -> {
            if (event.getEvent() == MrcpEventName.SPEAK_COMPLETE) {
                LOG.info("Goodbye prompt completed for Call-ID: {}, hanging up call", callId);
                SipRequest origInvite = Optional.ofNullable(session)
                        .map(s -> (SipRequest) s.getAttribute("originalInvite"))
                        .orElse(null);
                SipResponse origOk = Optional.ofNullable(session)
                        .map(s -> (SipResponse) s.getAttribute("originalOk"))
                        .orElse(null);
                terminateAndSendBye(callId, session, origInvite, origOk);
            }
        }).subscribe();
    }

    @OnBye("mrcp")
    public Mono<SipResponse> onMrcpBye(SipRequest request, @SipCallId String callId, SipSession session) {
        cleanupCall(callId, session);
        return Mono.just(request.createResponse(SipStatus.OK));
    }

    @OnBye("ai")
    public Mono<SipResponse> onAiBye(SipRequest request, @SipCallId String callId, SipSession session) {
        cleanupCall(callId, session);
        return Mono.just(request.createResponse(SipStatus.OK));
    }

    private void cleanupCall(String callId, SipSession session) {
        LOG.info("Cleaning up AI Assistant Call-ID: {}", callId);
        Optional.ofNullable(activeSynths.remove(callId)).ifPresent(s -> {
            channelManager.unregisterResource(s.getChannelId());
            s.close();
        });
        Optional.ofNullable(activeRecogs.remove(callId)).ifPresent(r -> {
            channelManager.unregisterResource(r.getChannelId());
            r.close();
        });
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
            return;
        }

        try {
            SipUri targetUri = Optional.ofNullable(originalInvite.getContact())
                    .filter(Predicate.not(String::isEmpty))
                    .map(SipUri::extractUri)
                    .or(() -> Optional.ofNullable(originalInvite.getFrom()).map(SipUri::extractUri))
                    .orElseGet(originalInvite::getUri);

            SipRequest bye = new SipRequest(SipMethod.BYE, targetUri);
            bye.setRemoteAddress(originalInvite.getRemoteAddress());
            bye.setTransport(originalInvite.getTransport());

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

            String userPart = Optional.ofNullable(session)
                    .map(s -> (String) s.getAttribute("userPart"))
                    .orElse("ai");

            bye.getHeaders().setContact("<sip:" + userPart + "@" + host + ":" + port + ">");

            for (String route : originalInvite.getHeaders().getAll(SipHeaders.RECORD_ROUTE)) {
                bye.getHeaders().add(SipHeaders.ROUTE, route);
            }

            sipServer.send(bye, originalInvite.getRemoteAddress());
            LOG.info("Sent BYE to {} for Call-ID: {}", originalInvite.getRemoteAddress(), callId);
        } catch (Exception e) {
            LOG.warn("Failed sending in-dialog BYE for Call-ID: {}", callId, e);
        }
    }

    private String resolveAdvertisedIp() {
        return Optional.ofNullable(sipServer)
                .map(SipNettyServer::getConfiguration)
                .map(net.pilgrim.sip.config.SipServerConfiguration::resolveAdvertisedIp)
                .filter(Predicate.not(String::isBlank))
                .orElse("127.0.0.1");
    }

    private static Optional<String> parseConnectionHost(String connectionLine) {
        if (connectionLine == null || connectionLine.isBlank()) {
            return Optional.empty();
        }
        String[] parts = connectionLine.trim().split("\\s+");
        if (parts.length >= 3) {
            return Optional.of(parts[2]);
        }
        return Optional.empty();
    }
}
