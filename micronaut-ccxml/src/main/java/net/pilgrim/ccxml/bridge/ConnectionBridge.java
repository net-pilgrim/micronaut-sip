package net.pilgrim.ccxml.bridge;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Interface bridging CCXML connection control to SIP signaling (e.g. micronaut-sip).
 */
public interface ConnectionBridge {

    /**
     * Originates an outbound call leg (SIP INVITE).
     *
     * @param dest destination URI or telephone number
     * @param callerId optional caller identifier
     * @param timeout connection timeout
     * @param hints application or protocol hints
     * @return Mono emitting the newly assigned connectionId
     */
    Mono<String> createCall(String dest, String callerId, Duration timeout, Map<String, Object> hints);

    /**
     * Accepts an incoming alerting call leg (SIP 200 OK / 180 Ringing).
     *
     * @param connectionId the connection identifier
     * @return Mono completing when accepted
     */
    Mono<Void> acceptCall(String connectionId);

    /**
     * Rejects an alerting call leg with an appropriate SIP failure code.
     *
     * @param connectionId the connection identifier
     * @param statusCode SIP status code (e.g. 486 Busy Here, 603 Decline)
     * @param reason textual failure reason
     * @return Mono completing when rejected
     */
    Mono<Void> rejectCall(String connectionId, int statusCode, String reason);

    /**
     * Redirects the call leg to an alternate destination (SIP 302 Moved Temporarily).
     *
     * @param connectionId the connection identifier
     * @param dest redirection destination URI
     * @param reason optional reason
     * @return Mono completing when redirected
     */
    Mono<Void> redirectCall(String connectionId, String dest, String reason);

    /**
     * Terminates an active or alerting call leg (SIP BYE or CANCEL).
     *
     * @param connectionId the connection identifier
     * @param reason disconnect reason
     * @return Mono completing when disconnected
     */
    Mono<Void> disconnectCall(String connectionId, String reason);
}
