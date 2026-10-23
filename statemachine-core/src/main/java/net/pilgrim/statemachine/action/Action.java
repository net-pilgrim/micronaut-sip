package net.pilgrim.statemachine.action;

import net.pilgrim.statemachine.model.Event;
import net.pilgrim.statemachine.runtime.StateMachineSession;

/**
 * Common executable action interface executed when transitions or state entry/exit blocks fire.
 */
@FunctionalInterface
public interface Action {

    /**
     * Executes this action within the context of an active state machine session.
     *
     * @param session the active state machine session
     * @param event the event triggering this execution
     */
    void execute(StateMachineSession session, Event event);
}
