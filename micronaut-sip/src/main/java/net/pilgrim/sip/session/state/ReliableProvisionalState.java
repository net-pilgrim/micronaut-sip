package net.pilgrim.sip.session.state;

/**
 * Orthogonal sub-machine state modeling RFC 3262 100rel provisional reliability.
 */
public enum ReliableProvisionalState {
    /**
     * No reliable provisional response is pending.
     */
    NONE,

    /**
     * A 1xx provisional response with RSeq has been sent, awaiting PRACK confirmation.
     */
    AWAITING_PRACK,

    /**
     * Matching PRACK has been received and confirmed.
     */
    PRACK_RESOLVED
}
