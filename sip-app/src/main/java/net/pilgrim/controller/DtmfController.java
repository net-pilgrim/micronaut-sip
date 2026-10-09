package net.pilgrim.controller;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.dtmf.DtmfSignal;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.transport.SipNettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Controller handling instant messaging (RFC 3428 MESSAGE) and mid-dialog
 * DTMF relay signaling (RFC 2976 / RFC 6086 INFO).
 */
@SipController
public class DtmfController extends BaseSipController {

    private static final Logger LOG = LoggerFactory.getLogger(DtmfController.class);

    public DtmfController() {
        this(null, null);
    }

    @Inject
    public DtmfController(@Nullable SipNettyServer sipServer,
                          @Nullable SipServerConfiguration serverConfig) {
        super(sipServer, serverConfig);
    }

    /**
     * Handles SIP MESSAGE requests (RFC 3428), including DTMF tones transmitted in message bodies.
     */
    @OnMessage
    public Mono<SipResponse> onMessage(SipRequest request,
                                       @SipFrom String from,
                                       @SipBody String messageBody,
                                       @SipDtmf DtmfSignal dtmf,
                                       SipSession session) {
        return Optional.ofNullable(dtmf)
                .map(signal -> {
                    LOG.info("Received DTMF via MESSAGE from {}: digit='{}', duration={}ms",
                            from, signal.getDigit(), signal.getDuration());
                    Optional.ofNullable(session).ifPresent(s -> recordDtmfInSession(s, signal));
                    SipResponse ok = SipResponse.ok(request);
                    ok.getHeaders().set("X-Received-DTMF", String.valueOf(signal.getDigit()));
                    return Mono.just(ok);
                })
                .orElseGet(() -> {
                    LOG.info("Received MESSAGE from {}: '{}'", from, messageBody);
                    return Mono.just(SipResponse.ok(request));
                });
    }

    /**
     * Handles mid-dialog SIP INFO requests (RFC 2976 / RFC 6086), including DTMF relay signaling.
     */
    @OnInfo
    public Mono<SipResponse> onInfo(SipRequest request,
                                    @SipCallId String callId,
                                    @SipFrom String from,
                                    @SipDtmf DtmfSignal dtmf,
                                    SipSession session) {
        return Optional.ofNullable(dtmf)
                .map(signal -> {
                    LOG.info("Received DTMF via INFO for Call-ID: {} from: {}: digit='{}', duration={}ms, volume={}",
                            callId, from, signal.getDigit(), signal.getDuration(), signal.getVolume());
                    Optional.ofNullable(session).ifPresent(s -> recordDtmfInSession(s, signal));
                    SipResponse ok = SipResponse.ok(request);
                    ok.getHeaders().set("X-Received-DTMF", String.valueOf(signal.getDigit()));
                    return Mono.just(ok);
                })
                .orElseGet(() -> {
                    LOG.info("Received INFO for Call-ID: {} from: {} with body: '{}'", callId, from, request.getBodyAsString());
                    return Mono.just(SipResponse.ok(request));
                });
    }

    private void recordDtmfInSession(SipSession session, DtmfSignal dtmf) {
        String existing = Optional.ofNullable((String) session.getAttribute("dtmfDigits")).orElse("");
        session.setAttribute("dtmfDigits", existing + dtmf.getDigit());
        session.setAttribute("lastDtmf", dtmf);
    }
}
