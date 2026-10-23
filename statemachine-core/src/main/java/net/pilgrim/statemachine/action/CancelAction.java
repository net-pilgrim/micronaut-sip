package net.pilgrim.statemachine.action;

import net.pilgrim.statemachine.model.Event;
import net.pilgrim.statemachine.runtime.StateMachineSession;

/**
 * Action cancelling a scheduled event by sendid.
 */
public record CancelAction(String sendid) implements Action {

    @Override
    public void execute(StateMachineSession session, Event event) {
        if (sendid != null) {
            session.cancelScheduledEvent(sendid);
        }
    }
}
