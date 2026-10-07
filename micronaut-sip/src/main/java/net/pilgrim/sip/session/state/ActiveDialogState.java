package net.pilgrim.sip.session.state;

import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.session.SipSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Superstate (composite state) representing active SIP dialogs in the Harel statechart.
 * Handles events common to all active dialog states (BYE, session timeout).
 */
public abstract sealed class ActiveDialogState implements SipDialogState permits
        EarlyDialogState,
        ConfirmedDialogState {

    private static final Logger LOG = LoggerFactory.getLogger(ActiveDialogState.class);

    @Override
    public DialogTransitionResult handleBye(SipSession session, SipRequest request) {
        LOG.info("Dialog {} received BYE in active state {}, transitioning to TERMINATED",
                session.getCallId(), getId());
        SipSession.State prev = getId();
        session.transitionTo(new TerminatedDialogState());
        return DialogTransitionResult.success(prev, SipSession.State.TERMINATED, "Dialog terminated by BYE");
    }

    @Override
    public DialogTransitionResult handleTimeout(SipSession session, String reason) {
        LOG.info("Dialog {} timed out in active state {} (reason: {}), transitioning to TERMINATED",
                session.getCallId(), getId(), reason);
        SipSession.State prev = getId();
        session.transitionTo(new TerminatedDialogState());
        return DialogTransitionResult.success(prev, SipSession.State.TERMINATED, "Dialog terminated due to timeout: " + reason);
    }
}
