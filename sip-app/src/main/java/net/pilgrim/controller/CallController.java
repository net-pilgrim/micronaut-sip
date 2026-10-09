package net.pilgrim.controller;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.rtp.RtpPacketizer;
import net.pilgrim.sip.rtp.RtpStreamSender;
import net.pilgrim.sip.rtp.codec.RtpCodec;
import net.pilgrim.sip.rtp.codec.RtpCodecRegistry;
import net.pilgrim.sip.rtp.media.RtpMediaManager;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.sip.sdp.SdpMessage;
import net.pilgrim.sip.sdp.SdpNegotiator;
import net.pilgrim.sip.sdp.SdpParser;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.transport.SipNettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Primary reactive SIP Controller handling standard two-party call setup (INVITE),
 * confirmation (ACK), termination (BYE), setup cancellation (CANCEL),
 * options query (OPTIONS), and reliable provisional acknowledgement (PRACK).
 */
@SipController
public class CallController extends BaseSipController {

    private static final Logger LOG = LoggerFactory.getLogger(CallController.class);
    private static final SdpNegotiator SDP_NEGOTIATOR = new SdpNegotiator();
    private static final SdpParser SDP_PARSER = new SdpParser();
    private static final RtpCodecRegistry RTP_CODECS = RtpCodecRegistry.withG711Defaults();

    private final RtpMediaManager rtpMediaManager;

    public CallController() {
        this(new RtpMediaManager(), null, null);
    }

    public CallController(RtpMediaManager rtpMediaManager) {
        this(rtpMediaManager, null, null);
    }

    @Inject
    public CallController(RtpMediaManager rtpMediaManager,
                          @Nullable SipNettyServer sipServer,
                          @Nullable SipServerConfiguration serverConfig) {
        super(sipServer, serverConfig);
        this.rtpMediaManager = rtpMediaManager != null ? rtpMediaManager : new RtpMediaManager();
    }

    public RtpMediaManager getRtpMediaManager() {
        return rtpMediaManager;
    }

    /**
     * Handles INVITE requests reactively.
     * Emits 180 Ringing followed by 200 OK with SDP session description.
     */
    @OnInvite
    public Flux<SipResponse> onInvite(SipRequest request,
                                      @SipCallId String callId,
                                      @SipBody String sdpOffer,
                                      SipSession session) {
        LOG.info("Received INVITE for Call-ID: {} from: {} to: {}", callId, request.getFrom(), request.getTo());
        if (sdpOffer != null && !sdpOffer.isEmpty()) {
            LOG.info("Received SDP offer ({} bytes)", sdpOffer.length());
        }

        if (session != null) {
            session.handleInvite(request, sdpOffer);
        }

        int localAudioPort = 49170;
        try {
            RtpMediaSession mediaSession = rtpMediaManager.createSession(callId);
            localAudioPort = mediaSession.getLocalPort();
        } catch (Exception e) {
            LOG.warn("Failed to allocate dynamic Netty RTP port for Call-ID: {}", callId, e);
        }

        // 180 Ringing provisional response
        SipResponse ringing = SipResponse.ringing(request);
        if (session != null) {
            session.handleProvisional(ringing);
        }
        String advertisedIp = resolveAdvertisedIp();
        String contactUri = buildContactUri(request);

        boolean require100rel = request.getHeaders().containsToken(SipHeaders.REQUIRE, "100rel");
        boolean supported100rel = request.getHeaders().containsToken(SipHeaders.SUPPORTED, "100rel");
        boolean reliable100rel = require100rel || supported100rel;

        Sinks.One<Void> prackSink = null;
        if (reliable100rel) {
            ringing.getHeaders().set(SipHeaders.REQUIRE, "100rel");
            ringing.getHeaders().setRSeq(1);
            ringing.getHeaders().setContact(contactUri);
            if (session != null) {
                prackSink = Sinks.one();
                session.getReliableContext().initiate(1L, request.getCSeqNumber(),
                        request.getMethod() != null ? request.getMethod().name() : "INVITE", prackSink);
                session.setAttribute("rseq", 1L);
                session.setAttribute("cseqNumber", request.getCSeqNumber());
                session.setAttribute("cseqMethod", request.getMethod() != null ? request.getMethod().name() : "INVITE");
                session.setAttribute("prackSink", prackSink);
            }
        }

        // 200 OK carries SDP answer for early-offer INVITE and SDP offer for late-offer INVITE.
        SipResponse ok;
        if (sdpOffer != null && !sdpOffer.isBlank()) {
            String answer = SDP_NEGOTIATOR.createAnswer(sdpOffer, localAudioPort, advertisedIp);
            if (session != null) {
                session.setAttribute("sdpAnswer", answer);
            }
            ok = SipResponse.ok(request, answer, "application/sdp");
        } else {
            if (session != null) {
                session.setAttribute("awaitingAckSdpAnswer", true);
            }
            String localOffer = SDP_NEGOTIATOR.createOffer(localAudioPort, advertisedIp);
            if (session != null) {
                session.setAttribute("localSdpOffer", localOffer);
            }
            ok = SipResponse.ok(request, localOffer, "application/sdp");
        }
        ok.getHeaders().setContact(contactUri);
        if (ringing.getTo() != null) {
            ok.getHeaders().setTo(ringing.getTo());
        }

        long delayMs = 300;
        String pickupDelay = request.getHeaders().get("X-Pickup-Delay");
        if (pickupDelay != null) {
            try {
                delayMs = Long.parseLong(pickupDelay);
            } catch (NumberFormatException ignored) {}
        }

        Mono<SipResponse> delayedOk = Mono.just(ok).delayElement(Duration.ofMillis(delayMs));
        if (prackSink != null) {
            delayedOk = Mono.when(
                    prackSink.asMono().timeout(Duration.ofSeconds(5), Mono.empty()),
                    Mono.delay(Duration.ofMillis(delayMs))
            ).thenReturn(ok);
        }

        // Reactively emit 180 Ringing immediately, then 200 OK after delayMs (simulating call pickup)
        return Flux.concat(
                Mono.just(ringing),
                delayedOk
        );
    }

    /**
     * Handles ACK confirming call setup.
     * Does not return a response per RFC 3261 Section 17.2.1.
     */
    @OnAck
    public void onAck(SipRequest request,
                      @SipCallId String callId,
                      @SipBody String ackSdpAnswer,
                      SipSession session) {
        if (session != null && session.isTerminated()) {
            LOG.info("Received ACK for terminated Call-ID: {}, ignoring state change.", callId);
            return;
        }
        if (session != null) {
            var transition = session.handleAck(request, ackSdpAnswer);
            if (transition.isConfirmed()) {
                LOG.info("Received ACK for Call-ID: {}, call session is now CONFIRMED.", callId);
                maybeSendRtpProbe(callId, session);
            }
        } else {
            LOG.info("Received ACK for Call-ID: {} (no session).", callId);
        }
    }

    /**
     * Handles BYE terminating an active call.
     * Returns a reactive Mono emitting 200 OK.
     */
    @OnBye
    public Mono<SipResponse> onBye(SipRequest request,
                                   @SipCallId String callId,
                                   SipSession session) {
        LOG.info("Received BYE for Call-ID: {}, terminating session.", callId);
        if (session != null) {
            session.handleBye(request);
        }
        rtpMediaManager.terminateSession(callId);
        return Mono.just(SipResponse.ok(request));
    }

    /**
     * Handles CANCEL request to terminate call setup (RFC 3261 §9).
     */
    @OnCancel
    public void onCancel(SipRequest request,
                         @SipCallId String callId,
                         SipSession session) {
        LOG.info("Received CANCEL for Call-ID: {}, terminating session.", callId);
        if (session != null) {
            session.handleCancel(request);
        }
        rtpMediaManager.terminateSession(callId);
    }

    /**
     * Handles PRACK (Provisional Response Acknowledgement) per RFC 3262.
     * Validates the RAck header against the session, completes any pending PRACK sink,
     * and returns 200 OK to acknowledge the PRACK request.
     */
    @OnPrack
    public Mono<SipResponse> onPrack(SipRequest request,
                                     @SipCallId String callId,
                                     @SipHeader(value = "RAck", required = false) String rack,
                                     @SipBody String prackBody,
                                     SipSession session) {
        LOG.info("Received PRACK for Call-ID: {} RAck: {}", callId, rack);
        if (session == null) {
            LOG.warn("PRACK received for unknown Call-ID: {}", callId);
            return Mono.just(SipResponse.transactionDoesNotExist(request));
        }

        var transition = session.handlePrack(request, rack, prackBody);
        if (transition.isRejected()) {
            LOG.warn("PRACK RAck mismatch or rejected for Call-ID: {}: {}", callId, transition.message());
            return Mono.just(SipResponse.transactionDoesNotExist(request));
        }

        return Mono.just(SipResponse.ok(request));
    }

    /**
     * Handles OPTIONS request for capability querying.
     */
    @OnOptions
    public SipResponse onOptions(SipRequest request) {
        LOG.info("Received OPTIONS request from {}", request.getFrom());
        SipResponse response = SipResponse.ok(request);
        response.getHeaders().set(SipHeaders.ALLOW, "INVITE, ACK, BYE, CANCEL, OPTIONS, REGISTER, MESSAGE, INFO, PRACK");
        response.getHeaders().set(SipHeaders.SUPPORTED, "replaces, 100rel");
        response.getHeaders().set(SipHeaders.RECV_INFO, "dtmf");
        return response;
    }

    private void maybeSendRtpProbe(String callId, SipSession session) {
        if (session == null) {
            return;
        }
        String remoteSdp = session.getAttribute("sdpOffer");
        if (remoteSdp == null || remoteSdp.isBlank()) {
            remoteSdp = session.getAttribute("sdpAnswer");
        }
        if (remoteSdp == null || remoteSdp.isBlank()) {
            return;
        }

        SdpMessage parsed;
        try {
            parsed = SDP_PARSER.parse(remoteSdp);
        } catch (IllegalArgumentException e) {
            LOG.warn("Cannot parse remote SDP for RTP probe, Call-ID: {}", callId, e);
            return;
        }

        SdpMessage.MediaDescription audio = parsed.findFirstAudioMedia();
        if (audio == null || audio.getPort() <= 0) {
            return;
        }

        // RFC 3264 hold semantics: if remote offered sendonly or inactive (so local is recvonly/inactive),
        // do not send RTP packets!
        String remoteDirection = audio.getAttributes().stream()
                .filter(a -> a.equalsIgnoreCase("sendrecv") || a.equalsIgnoreCase("sendonly")
                        || a.equalsIgnoreCase("recvonly") || a.equalsIgnoreCase("inactive"))
                .findFirst()
                .orElse(parsed.getDirectionAttribute());
        if (remoteDirection != null) {
            String lower = remoteDirection.trim().toLowerCase(Locale.ROOT);
            if (lower.equals("sendonly") || lower.equals("inactive")) {
                LOG.info("Call-ID {} is on hold (remote direction={}), suppressing RTP probe", callId, lower);
                return;
            }
        }

        String localAnswer = session.getAttribute("sdpAnswer");
        if (localAnswer != null && !localAnswer.isBlank()) {
            try {
                SdpMessage localParsed = SDP_PARSER.parse(localAnswer);
                String localDir = localParsed.getDirectionAttribute();
                SdpMessage.MediaDescription localAudio = localParsed.findFirstAudioMedia();
                if (localAudio != null && localAudio.getAttributes() != null) {
                    for (String a : localAudio.getAttributes()) {
                        if (a.equalsIgnoreCase("sendrecv") || a.equalsIgnoreCase("sendonly")
                                || a.equalsIgnoreCase("recvonly") || a.equalsIgnoreCase("inactive")) {
                            localDir = a.toLowerCase(Locale.ROOT);
                        }
                    }
                }
                if (localDir != null) {
                    String lower = localDir.trim().toLowerCase(Locale.ROOT);
                    if (lower.equals("recvonly") || lower.equals("inactive")) {
                        LOG.info("Call-ID {} local direction is {}, suppressing RTP probe", callId, lower);
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }

        Optional<RtpCodec> codec = audio.getFormats().stream()
                .map(this::parsePayloadType)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(RTP_CODECS::findByPayloadType)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
        if (codec.isEmpty()) {
            return;
        }

        String host = parseConnectionHost(parsed.getConnection()).orElse("127.0.0.1");
        byte[] silencePcm16Le = new byte[160 * 2];
        RtpCodec selectedCodec = codec.get();

        Optional<RtpMediaSession> mediaSessionOpt = rtpMediaManager.findSession(callId);
        if (mediaSessionOpt.isPresent()) {
            try {
                RtpMediaSession mediaSession = mediaSessionOpt.get();
                mediaSession.setRemoteAddress(new InetSocketAddress(InetAddress.getByName(host), audio.getPort()));
                mediaSession.setCodec(selectedCodec);
                mediaSession.sendAudioFrame(silencePcm16Le, true).block(Duration.ofSeconds(2));
                LOG.info("Sent Netty RTP probe frame (codec={} pt={} {}:{}) for Call-ID: {}",
                        selectedCodec.name(), selectedCodec.payloadType(), host, audio.getPort(), callId);
                return;
            } catch (Exception e) {
                LOG.warn("Failed to send Netty RTP probe frame for Call-ID: {}, falling back", callId, e);
            }
        }

        RtpPacketizer packetizer = new RtpPacketizer(selectedCodec.payloadType(), selectedCodec.clockRate());
        try (RtpStreamSender sender = new RtpStreamSender(
                InetAddress.getByName(host),
                audio.getPort(),
                packetizer
        )) {
            sender.sendPcm16LeFrame(silencePcm16Le, selectedCodec, true);
            LOG.info("Sent fallback RTP probe frame (codec={} pt={} {}:{}) for Call-ID: {}",
                    selectedCodec.name(), selectedCodec.payloadType(), host, audio.getPort(), callId);
        } catch (UnknownHostException e) {
            LOG.warn("Failed to resolve RTP host '{}' for Call-ID: {}", host, callId, e);
        } catch (IllegalArgumentException | IOException e) {
            LOG.warn("Failed to send RTP probe frame for Call-ID: {}", callId, e);
        }
    }

    private Optional<Integer> parsePayloadType(String payloadType) {
        if (payloadType == null || payloadType.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(payloadType.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
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
}
