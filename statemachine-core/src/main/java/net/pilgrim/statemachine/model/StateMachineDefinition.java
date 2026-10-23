package net.pilgrim.statemachine.model;

import net.pilgrim.statemachine.action.Action;
import net.pilgrim.statemachine.action.VarAction;

import java.util.Collections;
import java.util.List;

/**
 * Immutable definition of an event-driven state machine.
 */
public record StateMachineDefinition(
        String id,
        String initialState,
        String stateVariable,
        List<VarAction> variables,
        List<Action> initActions,
        List<Transition> transitions
) {

    public StateMachineDefinition(
            String id,
            String initialState,
            String stateVariable,
            List<VarAction> variables,
            List<Action> initActions,
            List<Transition> transitions
    ) {
        this.id = id != null ? id : "statemachine";
        this.initialState = initialState != null ? initialState : "init";
        this.stateVariable = stateVariable != null && !stateVariable.isBlank() ? stateVariable : "current_state";
        this.variables = variables != null ? List.copyOf(variables) : Collections.emptyList();
        this.initActions = initActions != null ? List.copyOf(initActions) : Collections.emptyList();
        this.transitions = transitions != null ? List.copyOf(transitions) : Collections.emptyList();
    }
}
