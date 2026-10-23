package net.pilgrim.statemachine.action;

import net.pilgrim.statemachine.eval.ExpressionEvaluator;
import net.pilgrim.statemachine.model.Event;
import net.pilgrim.statemachine.runtime.StateMachineSession;

import java.util.Map;

/**
 * Action assigning a new value to a variable based on an expression.
 */
public record AssignAction(String name, String expr) implements Action {

    @Override
    public void execute(StateMachineSession session, Event event) {
        Object val = null;
        if (expr != null && !expr.isBlank()) {
            val = ExpressionEvaluator.evaluate(expr, session.getVariables(), event != null ? Map.of("event$", event) : Map.of());
        }
        session.setVariable(name, val);
    }
}
