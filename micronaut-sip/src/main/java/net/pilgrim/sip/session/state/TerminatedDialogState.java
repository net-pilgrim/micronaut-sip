package net.pilgrim.sip.session.state;

import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.session.SipSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Terminal sink state (RFC 3261 §12.3) in the Harel statechart model.
 * Once a dialog is terminated, it ceases to exist and cannot transition back to any active state.
 */
public final class TerminatedDialogState implements SipDialogState {

    private static final Logger LOG = LoggerFactory.getLogger(TerminatedDialogState.class);

    @Override
    public SipSession.State getId() {
        return SipSession.State.TERMINATED;
    }

    @Override
    public boolean isTerminal() {
        return true;
    }

    @Override
    public DialogTransitionResult handleInvite(SipSession session, SipRequest request, String sdpOffer) {
        LOG.warn("Received INVITE for already terminated dialog Call-ID: {}", session.getCallId());
        return DialogTransitionResult.rejected(getId(), "Dialog already terminated");
    }

    @Override
    public DialogTransitionResult handleProvisional(SipSession session, SipResponse response) {
        return DialogTransitionResult.noop(getId(), "Ignored provisional response on terminated dialog");
    }

    @Override
    public DialogTransitionResult handleAck(SipSession session, SipRequest request, String sdpAnswer) {
        LOG.info("Received late ACK for terminated dialog Call-ID: {}, ignoring state change", session.getCallId());
        return DialogTransitionResult.noop(getId(), "Late ACK dropped on terminated dialog");
    }

    @Override
    public DialogTransitionResult handlePrack(SipSession session, SipRequest request, String rack, String prackBody) {
        LOG.warn("Received PRACK for terminated dialog Call-ID: {}", session.getCallId());
        return DialogTransitionResult.rejected(getId(), "Dialog already terminated");
    }

    @Override
    public DialogTransitionResult handleBye(SipSession session, SipRequest request) {
        LOG.info("Received redundant BYE for already terminated dialog Call-ID: {}", session.getCallId());
        return DialogTransitionResult.noop(getId(), "Dialog already terminated");
    }

    @Override
    public DialogTransitionResult handleCancel(SipSession session, SipRequest request) {
        return DialogTransitionResult.noop(getId(), "Dialog already terminated");
    }

    @Override
    public DialogTransitionResult handleTimeout(SipSession session, String reason) {
        return DialogTransitionResult.noop(getId(), "Dialog already terminated");
    }
}
