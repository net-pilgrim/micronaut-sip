package net.pilgrim.controller;

import net.pilgrim.sip.rtp.RtpPacketizer;
import net.pilgrim.sip.rtp.RtpStreamSender;
import net.pilgrim.sip.rtp.codec.RtpCodec;
import net.pilgrim.sip.rtp.codec.RtpCodecRegistry;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.model.SipUri;
import net.pilgrim.sip.sdp.SdpMessage;
import net.pilgrim.sip.sdp.SdpNegotiator;
import net.pilgrim.sip.sdp.SdpParser;
import net.pilgrim.sip.session.SipSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Example reactive SIP Controller handling phone call setup (INVITE),
 * confirmation (ACK), termination (BYE), registration (REGISTER),
 * capabilities (OPTIONS), and instant messaging (MESSAGE).
 */
@SipController
public class CallController {

    private static final Logger LOG = LoggerFactory.getLogger(CallController.class);
    private static final SdpNegotiator SDP_NEGOTIATOR = new SdpNegotiator();
    private static final SdpParser SDP_PARSER = new SdpParser();
    private static final RtpCodecRegistry RTP_CODECS = RtpCodecRegistry.withG711Defaults();

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

        session.setState(SipSession.State.EARLY);
        session.setAttribute("sdpOffer", sdpOffer);
        session.setAttribute("awaitingAckSdpAnswer", false);

        // 180 Ringing provisional response
        SipResponse ringing = SipResponse.ringing(request);

        // 200 OK carries SDP answer for early-offer INVITE and SDP offer for late-offer INVITE.
        SipResponse ok;
        if (sdpOffer != null && !sdpOffer.isBlank()) {
            String answer = SDP_NEGOTIATOR.createAnswer(sdpOffer);
            session.setAttribute("sdpAnswer", answer);
            ok = SipResponse.ok(request, answer, "application/sdp");
        } else {
            session.setAttribute("awaitingAckSdpAnswer", true);
            String localOffer = SDP_NEGOTIATOR.createOffer();
            session.setAttribute("localSdpOffer", localOffer);
            ok = SipResponse.ok(request, localOffer, "application/sdp");
        }
        ok.getHeaders().setContact("<sip:127.0.0.1:" + request.getRemoteAddress().getPort() + ">");

        long delayMs = 300;
        String pickupDelay = request.getHeaders().get("X-Pickup-Delay");
        if (pickupDelay != null) {
            try {
                delayMs = Long.parseLong(pickupDelay);
            } catch (NumberFormatException ignored) {}
        }

        // Reactively emit 180 Ringing immediately, then 200 OK after delayMs (simulating call pickup)
        return Flux.concat(
                Mono.just(ringing),
                Mono.just(ok).delayElement(Duration.ofMillis(delayMs))
        );
    }

    /**
     * Handles INVITE to "slow" user without an immediate provisional response,
     * delaying 350ms to trigger the server's auto 100 Trying timer (at 200ms)
     * before emitting 200 OK.
     */
    @OnInvite("slow")
    public Mono<SipResponse> onSlowInvite(SipRequest request,
                                          @SipCallId String callId,
                                          @SipBody String sdpOffer,
                                          SipSession session) {
        LOG.info("Received slow INVITE for Call-ID: {}, delaying 350ms to trigger auto 100 Trying", callId);
        session.setState(SipSession.State.EARLY);
        session.setAttribute("sdpOffer", sdpOffer);
        session.setAttribute("awaitingAckSdpAnswer", false);
        SipResponse ok;
        if (sdpOffer != null && !sdpOffer.isBlank()) {
            String answer = SDP_NEGOTIATOR.createAnswer(sdpOffer);
            session.setAttribute("sdpAnswer", answer);
            ok = SipResponse.ok(request, answer, "application/sdp");
        } else {
            session.setAttribute("awaitingAckSdpAnswer", true);
            String localOffer = SDP_NEGOTIATOR.createOffer();
            session.setAttribute("localSdpOffer", localOffer);
            ok = SipResponse.ok(request, localOffer, "application/sdp");
        }
        ok.getHeaders().setContact("<sip:127.0.0.1:" + request.getRemoteAddress().getPort() + ">");
        return Mono.just(ok).delayElement(Duration.ofMillis(350));
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
        if (session != null && session.getState() == SipSession.State.TERMINATED) {
            LOG.info("Received ACK for terminated Call-ID: {}, ignoring state change.", callId);
            return;
        }
        if (session != null) {
            Boolean awaitingAckSdpAnswer = session.getAttribute("awaitingAckSdpAnswer");
            if (Boolean.TRUE.equals(awaitingAckSdpAnswer)) {
                String contentType = request.getContentType();
                if (contentType != null && contentType.equalsIgnoreCase("application/sdp")
                        && ackSdpAnswer != null && !ackSdpAnswer.isBlank()) {
                    session.setAttribute("sdpAnswer", ackSdpAnswer);
                    session.setAttribute("awaitingAckSdpAnswer", false);
                    LOG.info("Received SDP answer in ACK for Call-ID: {} ({} bytes).", callId, ackSdpAnswer.length());
                } else {
                    LOG.warn("Expected SDP answer in ACK for Call-ID: {}, but none was provided.", callId);
                }
            }
        }
        LOG.info("Received ACK for Call-ID: {}, call session is now CONFIRMED.", callId);
        if (session != null) {
            session.setState(SipSession.State.CONFIRMED);
        }
        maybeSendRtpProbe(callId, session);
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
        session.setState(SipSession.State.TERMINATED);
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
            session.setState(SipSession.State.TERMINATED);
        }
    }

    /**
     * Handles REGISTER requests from SIP endpoints.
     */
    @OnRegister
    public SipResponse onRegister(SipRequest request,
                                  @SipTo SipUri toUri,
                                  @SipParam(value = "transport", defaultValue = "udp") String transport,
                                  @SipHeader(value = "Contact", required = false) String contact) {
        String user = (toUri != null && toUri.getUser() != null) ? toUri.getUser() : "";
        LOG.info("Received REGISTER request for user: {} transport: {} Contact: {}", user, transport, contact);
        SipResponse response = SipResponse.ok(request);
        if (contact != null) {
            response.getHeaders().setContact(contact);
        }
        if (!user.isEmpty()) {
            response.getHeaders().set("X-Registered-User", user);
        }
        response.getHeaders().set("X-Transport-Param", transport);
        response.getHeaders().set(SipHeaders.EXPIRES, "3600");
        return response;
    }

    /**
     * Handles OPTIONS request for capability querying.
     */
    @OnOptions
    public SipResponse onOptions(SipRequest request) {
        LOG.info("Received OPTIONS request from {}", request.getFrom());
        SipResponse response = SipResponse.ok(request);
        response.getHeaders().set(SipHeaders.ALLOW, "INVITE, ACK, BYE, CANCEL, OPTIONS, REGISTER, MESSAGE");
        response.getHeaders().set(SipHeaders.SUPPORTED, "replaces, 100rel");
        return response;
    }

    /**
     * Handles MESSAGE instant messaging (RFC 3428).
     */
    @OnMessage
    public Mono<SipResponse> onMessage(SipRequest request,
                                       @SipFrom String from,
                                       @SipBody String messageBody) {
        LOG.info("Received MESSAGE from {}: '{}'", from, messageBody);
        return Mono.just(SipResponse.ok(request));
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
        RtpPacketizer packetizer = new RtpPacketizer(selectedCodec.payloadType(), selectedCodec.clockRate());
        try (RtpStreamSender sender = new RtpStreamSender(
                InetAddress.getByName(host),
                audio.getPort(),
                packetizer
        )) {
            sender.sendPcm16LeFrame(silencePcm16Le, selectedCodec, true);
            LOG.info("Sent RTP probe frame (codec={} pt={} {}:{}) for Call-ID: {}",
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
