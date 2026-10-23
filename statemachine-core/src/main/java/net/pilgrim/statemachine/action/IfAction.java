package net.pilgrim.statemachine.action;

import net.pilgrim.statemachine.eval.ExpressionEvaluator;
import net.pilgrim.statemachine.model.Event;
import net.pilgrim.statemachine.runtime.StateMachineSession;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Conditional action executing nested actions based on guard conditions.
 */
public record IfAction(
        String cond,
        List<Action> thenActions,
        List<ElseIf> elseIfs,
        Else elseBlock
) implements Action {

    public IfAction(String cond, List<Action> thenActions, List<ElseIf> elseIfs, Else elseBlock) {
        this.cond = cond;
        this.thenActions = thenActions != null ? List.copyOf(thenActions) : Collections.emptyList();
        this.elseIfs = elseIfs != null ? List.copyOf(elseIfs) : Collections.emptyList();
        this.elseBlock = elseBlock;
    }

    @Override
    public void execute(StateMachineSession session, Event event) {
        Map<String, Object> eventScope = event != null ? Map.of("event$", event) : Collections.emptyMap();
        if (ExpressionEvaluator.evaluateCondition(cond, session.getVariables(), eventScope)) {
            for (Action action : thenActions) {
                action.execute(session, event);
                if (session.isTerminated()) return;
            }
            return;
        }

        for (ElseIf elseIf : elseIfs) {
            if (ExpressionEvaluator.evaluateCondition(elseIf.cond(), session.getVariables(), eventScope)) {
                for (Action action : elseIf.actions()) {
                    action.execute(session, event);
                    if (session.isTerminated()) return;
                }
                return;
            }
        }

        if (elseBlock != null) {
            for (Action action : elseBlock.actions()) {
                action.execute(session, event);
                if (session.isTerminated()) return;
            }
        }
    }
}
