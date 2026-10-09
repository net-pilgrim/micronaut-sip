package net.pilgrim.controller;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import net.pilgrim.sip.annotation.OnInvite;
import net.pilgrim.sip.annotation.SipBody;
import net.pilgrim.sip.annotation.SipCallId;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.rtp.media.RtpMediaManager;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.sip.sdp.SdpNegotiator;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.transport.SipNettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Controller handling high-latency callee scenarios (INVITE to "slow"),
 * delaying the final response by 350ms to verify that the server's non-blocking
 * automatic 100 Trying timer (at 200ms) fires properly.
 */
@SipController
public class SlowCallController extends BaseSipController {

    private static final Logger LOG = LoggerFactory.getLogger(SlowCallController.class);
    private static final SdpNegotiator SDP_NEGOTIATOR = new SdpNegotiator();

    private final RtpMediaManager rtpMediaManager;

    public SlowCallController() {
        this(new RtpMediaManager(), null, null);
    }

    @Inject
    public SlowCallController(RtpMediaManager rtpMediaManager,
                              @Nullable SipNettyServer sipServer,
                              @Nullable SipServerConfiguration serverConfig) {
        super(sipServer, serverConfig);
        this.rtpMediaManager = rtpMediaManager != null ? rtpMediaManager : new RtpMediaManager();
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
        if (session != null) {
            session.handleInvite(request, sdpOffer);
            session.setState(SipSession.State.EARLY);
        }

        int localAudioPort = 49170;
        try {
            RtpMediaSession mediaSession = rtpMediaManager.createSession(callId);
            localAudioPort = mediaSession.getLocalPort();
        } catch (Exception e) {
            LOG.warn("Failed to allocate dynamic Netty RTP port for slow Call-ID: {}", callId, e);
        }

        String advertisedIp = resolveAdvertisedIp();
        String contactUri = buildContactUri(request);

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
        return Mono.just(ok).delayElement(Duration.ofMillis(350));
    }
}
