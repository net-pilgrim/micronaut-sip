package net.pilgrim.sip.session.state;

import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.session.SipSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confirmed dialog substate (RFC 3261 §12.1) established by 2xx response + ACK.
 * Handles re-INVITE session modification, mid-dialog INFO/UPDATE, and BYE termination.
 */
public final class ConfirmedDialogState extends ActiveDialogState {

    private static final Logger LOG = LoggerFactory.getLogger(ConfirmedDialogState.class);

    @Override
    public SipSession.State getId() {
        return SipSession.State.CONFIRMED;
    }

    @Override
    public DialogTransitionResult handleInvite(SipSession session, SipRequest request, String sdpOffer) {
        LOG.info("Processing re-INVITE for confirmed Call-ID: {}", session.getCallId());
        if (sdpOffer != null && !sdpOffer.isBlank()) {
            session.getOfferAnswerContext().setOffer(sdpOffer);
        }
        return DialogTransitionResult.noop(getId(), "Re-INVITE session modification in CONFIRMED state");
    }

    @Override
    public DialogTransitionResult handleProvisional(SipSession session, SipResponse response) {
        return DialogTransitionResult.noop(getId(), "Provisional response in CONFIRMED state (re-INVITE)");
    }

    @Override
    public DialogTransitionResult handleAck(SipSession session, SipRequest request, String sdpAnswer) {
        if (session.getOfferAnswerContext().isAwaitingAckAnswer() && sdpAnswer != null && !sdpAnswer.isBlank()) {
            session.getOfferAnswerContext().setAnswer(sdpAnswer);
        }
        return DialogTransitionResult.noop(getId(), "Duplicate ACK acknowledged in CONFIRMED state");
    }

    @Override
    public DialogTransitionResult handlePrack(SipSession session, SipRequest request, String rack, String prackBody) {
        session.getReliableContext().completePrack();
        return DialogTransitionResult.noop(getId(), "PRACK processed in CONFIRMED state");
    }

    @Override
    public DialogTransitionResult handleCancel(SipSession session, SipRequest request) {
        LOG.warn("CANCEL received for already confirmed dialog Call-ID: {}, ignoring per RFC 3261 §9", session.getCallId());
        return DialogTransitionResult.rejected(getId(), "CANCEL ignored on CONFIRMED dialog");
    }
}
