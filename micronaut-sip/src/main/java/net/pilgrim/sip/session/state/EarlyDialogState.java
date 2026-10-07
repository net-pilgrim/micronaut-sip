package net.pilgrim.sip.session.state;

import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.session.SipSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Early dialog substate (RFC 3261 §12.1) established by provisional responses with a To tag.
 * Handles 100rel PRACK validations and transitions to CONFIRMED on ACK.
 */
public final class EarlyDialogState extends ActiveDialogState {

    private static final Logger LOG = LoggerFactory.getLogger(EarlyDialogState.class);

    @Override
    public SipSession.State getId() {
        return SipSession.State.EARLY;
    }

    @Override
    public DialogTransitionResult handleInvite(SipSession session, SipRequest request, String sdpOffer) {
        if (sdpOffer != null && !sdpOffer.isBlank()) {
            session.getOfferAnswerContext().setOffer(sdpOffer);
        }
        return DialogTransitionResult.noop(getId(), "Provisional INVITE updated in EARLY state");
    }

    @Override
    public DialogTransitionResult handleProvisional(SipSession session, SipResponse response) {
        return DialogTransitionResult.noop(getId(), "Provisional response received in EARLY state");
    }

    @Override
    public DialogTransitionResult handleAck(SipSession session, SipRequest request, String sdpAnswer) {
        // Late-offer check: if waiting for SDP answer in ACK, consume it
        if (session.getOfferAnswerContext().isAwaitingAckAnswer()) {
            if (sdpAnswer != null && !sdpAnswer.isBlank()) {
                session.getOfferAnswerContext().setAnswer(sdpAnswer);
                LOG.info("Consumed SDP answer in ACK for Call-ID: {} ({} bytes)", session.getCallId(), sdpAnswer.length());
            } else {
                LOG.warn("Expected SDP answer in ACK for late-offer Call-ID: {}, but none was provided", session.getCallId());
            }
        }

        session.transitionTo(new ConfirmedDialogState());
        return DialogTransitionResult.success(getId(), SipSession.State.CONFIRMED, "Dialog confirmed by ACK");
    }

    @Override
    public DialogTransitionResult handlePrack(SipSession session, SipRequest request, String rack, String prackBody) {
        ReliableProvisionalContext relCtx = session.getReliableContext();
        if (rack != null && !rack.isBlank() && !relCtx.validateRack(rack)) {
            LOG.warn("PRACK RAck mismatch for Call-ID: {}: received '{}'", session.getCallId(), rack);
            return DialogTransitionResult.rejected(getId(), "RAck header mismatch");
        }

        if (prackBody != null && !prackBody.isBlank()) {
            session.getOfferAnswerContext().setAnswer(prackBody);
            LOG.info("Consumed SDP answer in PRACK for Call-ID: {} ({} bytes)", session.getCallId(), prackBody.length());
        }

        relCtx.completePrack();
        return DialogTransitionResult.noop(getId(), "PRACK validated and acknowledged");
    }

    @Override
    public DialogTransitionResult handleCancel(SipSession session, SipRequest request) {
        LOG.info("Early dialog for Call-ID: {} cancelled by CANCEL request", session.getCallId());
        session.getReliableContext().cancel();
        session.transitionTo(new TerminatedDialogState());
        return DialogTransitionResult.success(getId(), SipSession.State.TERMINATED, "Early dialog terminated by CANCEL");
    }
}
