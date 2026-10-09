package net.pilgrim.controller;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.model.SipUri;
import net.pilgrim.sip.transport.SipNettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Predicate;

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
        Optional<String> user = Optional.ofNullable(toUri)
                .map(SipUri::getUser)
                .filter(Predicate.not(String::isBlank));

        LOG.info("Received REGISTER request for user: {} transport: {} Contact: {}",
                user.orElse(""), transport, contact);

        SipResponse response = SipResponse.ok(request);
        Optional.ofNullable(contact).ifPresent(response.getHeaders()::setContact);
        user.ifPresent(u -> response.getHeaders().set("X-Registered-User", u));
        response.getHeaders().set("X-Transport-Param", transport);
        response.getHeaders().set(SipHeaders.EXPIRES, "3600");
        return response;
    }
}
