package net.pilgrim.sip.session.state;

import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.session.SipSession;

/**
 * Sealed interface for SIP dialog lifecycle states in the Harel statechart model.
 */
public sealed interface SipDialogState permits
        InitialDialogState,
        ActiveDialogState,
        TerminatedDialogState {

    /**
     * @return the enum state identifier for backward compatibility
     */
    SipSession.State getId();

    /**
     * @return true if this state is a terminal sink (TERMINATED)
     */
    default boolean isTerminal() {
        return false;
    }

    DialogTransitionResult handleInvite(SipSession session, SipRequest request, String sdpOffer);

    DialogTransitionResult handleProvisional(SipSession session, SipResponse response);

    DialogTransitionResult handleAck(SipSession session, SipRequest request, String sdpAnswer);

    DialogTransitionResult handlePrack(SipSession session, SipRequest request, String rack, String prackBody);

    DialogTransitionResult handleBye(SipSession session, SipRequest request);

    DialogTransitionResult handleCancel(SipSession session, SipRequest request);

    DialogTransitionResult handleTimeout(SipSession session, String reason);
}
