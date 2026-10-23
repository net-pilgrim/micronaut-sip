package net.pilgrim.statemachine.action;

import java.util.Collections;
import java.util.List;

public record ElseIf(String cond, List<Action> actions) {
    public ElseIf(String cond, List<Action> actions) {
        this.cond = cond;
        this.actions = actions != null ? List.copyOf(actions) : Collections.emptyList();
    }
}
