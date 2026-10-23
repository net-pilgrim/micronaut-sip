package net.pilgrim.statemachine.action;

import net.pilgrim.statemachine.model.Event;
import net.pilgrim.statemachine.runtime.StateMachineSession;

/**
 * Action terminating the state machine session.
 */
public record ExitAction(String expr) implements Action {

    @Override
    public void execute(StateMachineSession session, Event event) {
        session.terminate();
    }
}
