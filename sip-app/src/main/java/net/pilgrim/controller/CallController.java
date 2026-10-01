package net.pilgrim.controller;

import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.model.SipUri;
import net.pilgrim.sip.session.SipSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Example reactive SIP Controller handling phone call setup (INVITE),
 * confirmation (ACK), termination (BYE), registration (REGISTER),
 * capabilities (OPTIONS), and instant messaging (MESSAGE).
 */
@SipController
public class CallController {

    private static final Logger LOG = LoggerFactory.getLogger(CallController.class);

    private static final String BASE_SDP_ANSWER =
            "v=0\r\n" +
            "o=MicronautSIP 2890844526 2890844526 IN IP4 127.0.0.1\r\n" +
            "s=Call\r\n" +
            "c=IN IP4 127.0.0.1\r\n" +
            "t=0 0\r\n" +
            "m=audio 49170 RTP/AVP 0\r\n" +
            "a=rtpmap:0 PCMU/8000\r\n";

    private String createSdpAnswer(String offer) {
        String direction = "a=sendrecv\r\n";
        if (offer != null) {
            if (offer.contains("a=sendonly")) {
                direction = "a=recvonly\r\n";
            } else if (offer.contains("a=recvonly")) {
                direction = "a=sendonly\r\n";
            } else if (offer.contains("a=inactive")) {
                direction = "a=inactive\r\n";
            }
        }
        return BASE_SDP_ANSWER + direction;
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

        session.setState(SipSession.State.EARLY);
        session.setAttribute("sdpOffer", sdpOffer);

        // 180 Ringing provisional response
        SipResponse ringing = SipResponse.ringing(request);

        // 200 OK response with SDP answer body
        SipResponse ok = SipResponse.ok(request, createSdpAnswer(sdpOffer), "application/sdp");
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
        SipResponse ok = SipResponse.ok(request, createSdpAnswer(sdpOffer), "application/sdp");
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
                      SipSession session) {
        if (session != null && session.getState() == SipSession.State.TERMINATED) {
            LOG.info("Received ACK for terminated Call-ID: {}, ignoring state change.", callId);
            return;
        }
        LOG.info("Received ACK for Call-ID: {}, call session is now CONFIRMED.", callId);
        if (session != null) {
            session.setState(SipSession.State.CONFIRMED);
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
}
