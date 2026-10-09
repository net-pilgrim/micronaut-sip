package net.pilgrim.controller;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import net.pilgrim.sip.annotation.OnRegister;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.annotation.SipHeader;
import net.pilgrim.sip.annotation.SipParam;
import net.pilgrim.sip.annotation.SipTo;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.model.SipUri;
import net.pilgrim.sip.transport.SipNettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller handling SIP user registrations (REGISTER) per RFC 3261 Section 10.
 */
@SipController
public class RegistrationController extends BaseSipController {

    private static final Logger LOG = LoggerFactory.getLogger(RegistrationController.class);

    public RegistrationController() {
        this(null, null);
    }

    @Inject
    public RegistrationController(@Nullable SipNettyServer sipServer,
                                  @Nullable SipServerConfiguration serverConfig) {
        super(sipServer, serverConfig);
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
}
