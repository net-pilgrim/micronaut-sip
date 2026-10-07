package net.pilgrim.sip.session.state;

import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.session.SipSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initial pre-dialog state before any provisional or final response is generated.
 */
public final class InitialDialogState implements SipDialogState {

    private static final Logger LOG = LoggerFactory.getLogger(InitialDialogState.class);

    @Override
    public SipSession.State getId() {
        return SipSession.State.INITIAL;
    }

    @Override
    public DialogTransitionResult handleInvite(SipSession session, SipRequest request, String sdpOffer) {
        if (sdpOffer != null && !sdpOffer.isBlank()) {
            session.getOfferAnswerContext().setOffer(sdpOffer);
        }
        return DialogTransitionResult.noop(getId(), "Initial INVITE processed");
    }

    @Override
    public DialogTransitionResult handleProvisional(SipSession session, SipResponse response) {
        session.transitionTo(new EarlyDialogState());
        return DialogTransitionResult.success(getId(), SipSession.State.EARLY, "Transitioned to EARLY on provisional response");
    }

    @Override
    public DialogTransitionResult handleAck(SipSession session, SipRequest request, String sdpAnswer) {
        if (session.getOfferAnswerContext().isAwaitingAckAnswer() && sdpAnswer != null && !sdpAnswer.isBlank()) {
            session.getOfferAnswerContext().setAnswer(sdpAnswer);
        }
        session.transitionTo(new ConfirmedDialogState());
        return DialogTransitionResult.success(getId(), SipSession.State.CONFIRMED, "Dialog confirmed by ACK");
    }

    @Override
    public DialogTransitionResult handlePrack(SipSession session, SipRequest request, String rack, String prackBody) {
        LOG.warn("Received unexpected PRACK in INITIAL state for Call-ID: {}", session.getCallId());
        return DialogTransitionResult.rejected(getId(), "PRACK unexpected in INITIAL state");
    }

    @Override
    public DialogTransitionResult handleBye(SipSession session, SipRequest request) {
        LOG.warn("Received unexpected BYE in INITIAL state for Call-ID: {}", session.getCallId());
        session.transitionTo(new TerminatedDialogState());
        return DialogTransitionResult.success(getId(), SipSession.State.TERMINATED, "Terminated on BYE in INITIAL state");
    }

    @Override
    public DialogTransitionResult handleCancel(SipSession session, SipRequest request) {
        session.transitionTo(new TerminatedDialogState());
        return DialogTransitionResult.success(getId(), SipSession.State.TERMINATED, "Terminated on CANCEL");
    }

    @Override
    public DialogTransitionResult handleTimeout(SipSession session, String reason) {
        session.transitionTo(new TerminatedDialogState());
        return DialogTransitionResult.success(getId(), SipSession.State.TERMINATED, "Terminated due to timeout: " + reason);
    }
}
