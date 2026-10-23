package net.pilgrim.statemachine.model;

import net.pilgrim.statemachine.action.Action;
import net.pilgrim.statemachine.eval.ExpressionEvaluator;
import net.pilgrim.statemachine.runtime.StateMachineSession;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents a state machine transition triggered by an event in a given state with a guard condition.
 */
public record Transition(
        String event,
        String state,
        String target,
        String cond,
        List<Action> actions
) {

    public Transition(String event, String state, String target, String cond, List<Action> actions) {
        this.event = event;
        this.state = state;
        this.target = target;
        this.cond = cond;
        this.actions = actions != null ? List.copyOf(actions) : Collections.emptyList();
    }

    public boolean matches(String currentState, Event ev, Map<String, Object> variables) {
        // 1. State match
        if (state != null && !state.isBlank()) {
            boolean matched = false;
            String[] tokens = state.trim().split("\\s+");
            for (String s : tokens) {
                if (s.equals(currentState)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }

        // 2. Event match
        if (event != null && !event.isBlank() && !event.equals("*")) {
            String evName = ev != null ? ev.getName() : "";
            boolean matched = false;
            String[] eventTokens = event.trim().split("\\s+");
            for (String pat : eventTokens) {
                if (pat.equals(evName)) {
                    matched = true;
                    break;
                }
                if (pat.endsWith(".*")) {
                    String prefix = pat.substring(0, pat.length() - 2);
                    if (evName.equals(prefix) || evName.startsWith(prefix + ".")) {
                        matched = true;
                        break;
                    }
                } else if (evName.startsWith(pat + ".")) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }

        // 3. Condition match
        if (cond != null && !cond.isBlank()) {
            Map<String, Object> eventScope = ev != null ? Map.of("event$", ev) : Collections.emptyMap();
            return ExpressionEvaluator.evaluateCondition(cond, variables, eventScope);
        }

        return true;
    }

    public void execute(StateMachineSession session, Event ev) {
        for (Action action : actions) {
            action.execute(session, ev);
            if (session.isTerminated()) {
                return;
            }
        }
        if (target != null && !target.isBlank()) {
            session.setCurrentState(target);
        }
    }
}
