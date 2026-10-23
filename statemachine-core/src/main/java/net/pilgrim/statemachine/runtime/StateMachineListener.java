package net.pilgrim.statemachine.runtime;

import net.pilgrim.statemachine.model.Event;
import net.pilgrim.statemachine.model.Transition;

/**
 * Listener interface for lifecycle and transition callbacks on a StateMachineSession.
 */
public interface StateMachineListener {

    default void onStateChanged(String fromState, String toState, Event triggerEvent) {}

    default void onTransitionFired(Transition transition, Event event) {}

    default void onEventProcessed(Event event, boolean matched) {}

    default void onTerminated() {}
}
