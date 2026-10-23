package net.pilgrim.statemachine.action;

import net.pilgrim.statemachine.model.Event;
import net.pilgrim.statemachine.runtime.StateMachineSession;

/**
 * Action sending an asynchronous or scheduled event to the session or an external target.
 */
public record SendAction(
        String event,
        String target,
        String targettype,
        String sendid,
        String delay,
        String namelist
) implements Action {

    @Override
    public void execute(StateMachineSession session, Event currentEvent) {
        session.send(event, target, targettype, sendid, delay, namelist, currentEvent);
    }
}
