package net.pilgrim.sip.metrics;

import java.time.Duration;

/**
 * SPI for collecting and reporting SIP stack metrics.
 */
public interface SipMetrics {

    SipMetrics NOOP = new SipMetrics() {};

    /**
     * Called when a SIP request is received.
     *
     * @param method the SIP method (INVITE, ACK, BYE, CANCEL, etc.)
     * @param transport the transport protocol (udp, tcp, etc.)
     */
    default void requestReceived(String method, String transport) {}

    /**
     * Called when a SIP response is emitted.
     *
     * @param method the SIP method associated with the response
     * @param statusCode the numeric SIP status code (e.g. 200, 180, 487)
     * @param transport the transport protocol (udp, tcp, etc.)
     */
    default void responseSent(String method, int statusCode, String transport) {}

    /**
     * Called to record the execution duration of a SIP transaction.
     *
     * @param method the SIP method
     * @param statusCode the numeric SIP status code
     * @param duration the elapsed duration
     */
    default void requestDuration(String method, int statusCode, Duration duration) {}

    /**
     * Called when an incoming request is rejected before reaching a controller.
     *
     * @param reason the rejection reason (e.g. missing_mandatory_headers, message_too_large, transaction_does_not_exist)
     */
    default void requestRejected(String reason) {}

    /**
     * Updates the active/inactive state of a transport channel.
     *
     * @param transport the transport name ("udp" or "tcp")
     * @param active true if listening, false if stopped
     */
    default void setTransportActive(String transport, boolean active) {}
}
