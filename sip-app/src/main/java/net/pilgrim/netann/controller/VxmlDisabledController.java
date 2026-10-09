package net.pilgrim.netann.controller;

import io.micronaut.context.annotation.Requires;
import net.pilgrim.sip.annotation.OnInvite;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Fallback controller for dialog service indicator when VoiceXML is disabled.
 * Returns 488 Not Acceptable Here per RFC 4240 §2.
 */
@Requires(property = "netann.vxml.enabled", value = "false")
@SipController
public class VxmlDisabledController {

    private static final Logger LOG = LoggerFactory.getLogger(VxmlDisabledController.class);

    @OnInvite("dialog")
    public Mono<SipResponse> onDialogInvite(SipRequest request) {
        LOG.warn("Received INVITE for dialog when netann.vxml.enabled=false. Returning 488 per RFC 4240 §2.");
        return Mono.just(request.createResponse(488, "Not Acceptable Here"));
    }
}
