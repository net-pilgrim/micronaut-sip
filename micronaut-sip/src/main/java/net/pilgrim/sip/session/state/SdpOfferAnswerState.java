package net.pilgrim.sip.session.state;

/**
 * Orthogonal sub-machine state modeling RFC 3264 Offer/Answer negotiation.
 */
public enum SdpOfferAnswerState {
    /**
     * No offer or answer has been exchanged yet.
     */
    IDLE,

    /**
     * An SDP offer was received in incoming INVITE (Early-Offer flow).
     */
    OFFER_RECEIVED,

    /**
     * A local SDP offer was generated and sent in 200 OK (Late-Offer flow).
     */
    OFFER_SENT,

    /**
     * Waiting for remote SDP answer to be received in ACK (Late-Offer flow).
     */
    AWAITING_ACK_ANSWER,

    /**
     * Offer and answer have been exchanged and negotiated successfully.
     */
    STABLE,

    /**
     * Media stream is on hold (e.g. sendonly or inactive direction).
     */
    ON_HOLD
}
