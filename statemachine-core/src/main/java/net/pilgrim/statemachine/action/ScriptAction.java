package net.pilgrim.statemachine.action;

import net.pilgrim.statemachine.model.Event;
import net.pilgrim.statemachine.runtime.StateMachineSession;

/**
 * Action executing inline script statements or expression evaluations.
 */
public record ScriptAction(String content) implements Action {

    @Override
    public void execute(StateMachineSession session, Event event) {
        if (content != null && !content.isBlank()) {
            session.executeScript(content, event);
        }
    }
}
