package net.pilgrim.controller;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import net.pilgrim.sip.annotation.OnInvite;
import net.pilgrim.sip.annotation.SipBody;
import net.pilgrim.sip.annotation.SipCallId;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.rtp.codec.G711UlawCodec;
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;

/**
 * Controller handling early-media call flows (RFC 3960 / RFC 3261),
 * negotiating SDP answer via 183 Session Progress and streaming in-band
 * audible ringback tone over RTP during the provisional EARLY dialog state.
 */
@SipController
public class EarlyMediaController extends BaseSipController {

    private static final Logger LOG = LoggerFactory.getLogger(EarlyMediaController.class);
    private static final SdpNegotiator SDP_NEGOTIATOR = new SdpNegotiator();
    private static final SdpParser SDP_PARSER = new SdpParser();

    private final RtpMediaManager rtpMediaManager;

    public EarlyMediaController() {
        this(new RtpMediaManager(), null, null);
    }

    @Inject
    public EarlyMediaController(RtpMediaManager rtpMediaManager,
                                @Nullable SipNettyServer sipServer,
                                @Nullable SipServerConfiguration serverConfig) {
        super(sipServer, serverConfig);
        this.rtpMediaManager = rtpMediaManager != null ? rtpMediaManager : new RtpMediaManager();
    }

    /**
     * Handles INVITE to "early-media" or "ringback" endpoint (RFC 3960 / RFC 3261).
     * Emits a provisional 183 Session Progress response containing the SDP answer,
     * immediately starts streaming in-band early media (ringback tone) over RTP while
     * in the EARLY dialog state, and subsequently emits 200 OK after the early media duration.
     */
    @OnInvite("early-media")
    public Flux<SipResponse> onEarlyMediaInvite(SipRequest request,
                                                @SipCallId String callId,
                                                @SipBody String sdpOffer,
                                                SipSession session) {
        LOG.info("Received early-media INVITE for Call-ID: {} from: {}", callId, request.getFrom());
        if (session != null) {
            session.handleInvite(request, sdpOffer);
        }

        int localAudioPort = 49170;
        RtpMediaSession mediaSession = null;
        try {
            mediaSession = rtpMediaManager.createSession(callId);
            localAudioPort = mediaSession.getLocalPort();
        } catch (Exception e) {
            LOG.warn("Failed to allocate dynamic Netty RTP port for early-media Call-ID: {}", callId, e);
        }

        String advertisedIp = resolveAdvertisedIp();
        String contactUri = buildContactUri(request);

        String sdpAnswer = (sdpOffer != null && !sdpOffer.isBlank())
                ? SDP_NEGOTIATOR.createAnswer(sdpOffer, localAudioPort, advertisedIp)
                : SDP_NEGOTIATOR.createOffer(localAudioPort, advertisedIp);

        if (session != null) {
            session.setAttribute("sdpAnswer", sdpAnswer);
        }

        // 183 Session Progress provisional response carrying SDP Answer
        SipResponse sessionProgress = SipResponse.sessionProgress(request);
        sessionProgress.getHeaders().setContentType("application/sdp");
        sessionProgress.setBody(sdpAnswer);
        sessionProgress.getHeaders().setContact(contactUri);

        boolean require100rel = request.getHeaders().containsToken(SipHeaders.REQUIRE, "100rel");
        boolean supported100rel = request.getHeaders().containsToken(SipHeaders.SUPPORTED, "100rel");
        boolean reliable100rel = require100rel || supported100rel;

        Sinks.One<Void> prackSink = null;
        if (reliable100rel) {
            sessionProgress.getHeaders().set(SipHeaders.REQUIRE, "100rel");
            sessionProgress.getHeaders().setRSeq(1);
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

        if (session != null) {
            session.handleProvisional(sessionProgress);
        }

        // Configure early media duration (default: 800ms)
        long earlyMediaMs = 800;
        String customDuration = request.getHeaders().get("X-Early-Media-Duration");
        if (customDuration != null) {
            try {
                earlyMediaMs = Long.parseLong(customDuration);
            } catch (NumberFormatException ignored) {}
        }

        // Stream in-band ringback tone early media over RTP
        if (mediaSession != null && sdpOffer != null && !sdpOffer.isBlank()) {
            try {
                SdpMessage parsed = SDP_PARSER.parse(sdpOffer);
                SdpMessage.MediaDescription audio = parsed.findFirstAudioMedia();
                if (audio != null && audio.getPort() > 0) {
                    String host = parseConnectionHost(parsed.getConnection()).orElse("127.0.0.1");
                    mediaSession.setRemoteAddress(new InetSocketAddress(InetAddress.getByName(host), audio.getPort()));
                    mediaSession.setCodec(new G711UlawCodec());
                    byte[] ringbackPcm = generateRingbackTone((int) earlyMediaMs);
                    mediaSession.playAudio(ringbackPcm, null);
                    LOG.info("Started early media ringback audio streaming for Call-ID: {} to {}:{} ({} ms)",
                            callId, host, audio.getPort(), earlyMediaMs);
                }
            } catch (Exception e) {
                LOG.warn("Failed to initiate early media RTP streaming for Call-ID: {}", callId, e);
            }
        }

        // 200 OK final response (call answered)
        SipResponse ok = SipResponse.ok(request, sdpAnswer, "application/sdp");
        ok.getHeaders().setContact(contactUri);
        if (sessionProgress.getTo() != null) {
            ok.getHeaders().setTo(sessionProgress.getTo());
        }

        Mono<SipResponse> delayedOk = Mono.just(ok).delayElement(Duration.ofMillis(earlyMediaMs));
        if (prackSink != null) {
            delayedOk = Mono.when(
                    prackSink.asMono().timeout(Duration.ofSeconds(5), Mono.empty()),
                    Mono.delay(Duration.ofMillis(earlyMediaMs))
            ).thenReturn(ok);
        }

        return Flux.concat(
                Mono.just(sessionProgress),
                delayedOk
        );
    }

    @OnInvite("ringback")
    public Flux<SipResponse> onRingbackInvite(SipRequest request,
                                              @SipCallId String callId,
                                              @SipBody String sdpOffer,
                                              SipSession session) {
        return onEarlyMediaInvite(request, callId, sdpOffer, session);
    }

    /**
     * Generates a North American standard dual-frequency (440 Hz + 480 Hz) ringback tone
     * in linear PCM-16LE format (8000 Hz, mono).
     */
    public static byte[] generateRingbackTone(int durationMs) {
        int totalSamples = (int) (8000.0 * durationMs / 1000.0);
        byte[] pcm = new byte[totalSamples * 2];
        for (int i = 0; i < totalSamples; i++) {
            double t = i / 8000.0;
            double sample = 0.5 * Math.sin(2 * Math.PI * 440.0 * t) + 0.5 * Math.sin(2 * Math.PI * 480.0 * t);
            short s = (short) (sample * 16384);
            pcm[2 * i] = (byte) (s & 0xFF);
            pcm[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return pcm;
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
