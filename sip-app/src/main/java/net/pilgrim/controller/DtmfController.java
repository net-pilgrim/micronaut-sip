package net.pilgrim.controller;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import net.pilgrim.sip.annotation.OnInfo;
import net.pilgrim.sip.annotation.OnMessage;
import net.pilgrim.sip.annotation.SipBody;
import net.pilgrim.sip.annotation.SipCallId;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.annotation.SipDtmf;
import net.pilgrim.sip.annotation.SipFrom;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.dtmf.DtmfSignal;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.transport.SipNettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

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
        if (dtmf != null) {
            LOG.info("Received DTMF via MESSAGE from {}: digit='{}', duration={}ms", from, dtmf.getDigit(), dtmf.getDuration());
            if (session != null) {
                recordDtmfInSession(session, dtmf);
            }
            SipResponse ok = SipResponse.ok(request);
            ok.getHeaders().set("X-Received-DTMF", String.valueOf(dtmf.getDigit()));
            return Mono.just(ok);
        }
        LOG.info("Received MESSAGE from {}: '{}'", from, messageBody);
        return Mono.just(SipResponse.ok(request));
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
        if (dtmf != null) {
            LOG.info("Received DTMF via INFO for Call-ID: {} from: {}: digit='{}', duration={}ms, volume={}",
                    callId, from, dtmf.getDigit(), dtmf.getDuration(), dtmf.getVolume());
            if (session != null) {
                recordDtmfInSession(session, dtmf);
            }
            SipResponse ok = SipResponse.ok(request);
            ok.getHeaders().set("X-Received-DTMF", String.valueOf(dtmf.getDigit()));
            return Mono.just(ok);
        }
        LOG.info("Received INFO for Call-ID: {} from: {} with body: '{}'", callId, from, request.getBodyAsString());
        return Mono.just(SipResponse.ok(request));
    }

    private void recordDtmfInSession(SipSession session, DtmfSignal dtmf) {
        String existing = session.getAttribute("dtmfDigits");
        String updated = (existing != null ? existing : "") + dtmf.getDigit();
        session.setAttribute("dtmfDigits", updated);
        session.setAttribute("lastDtmf", dtmf);
    }
}
