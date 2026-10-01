package net.pilgrim.sip.transport;

import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;

import java.util.function.Consumer;

/**
 * Functional interface extending {@link Consumer} of {@link SipResponse} to allow sending
 * both outbound responses and outbound dialog requests (such as BYE on unacknowledged 2xx per RFC 3261 §14.1).
 */
public interface SipMessageSender extends Consumer<SipResponse> {

    /**
     * Sends a SIP message (request or response) over the underlying transport.
     *
     * @param message the SIP message to send
     */
    void sendMessage(SipMessage message);

    /**
     * Sends an outbound SIP request (e.g. BYE).
     *
     * @param request the SIP request to send
     */
    default void sendRequest(SipRequest request) {
        sendMessage(request);
    }

    @Override
    default void accept(SipResponse response) {
        sendMessage(response);
    }
}
