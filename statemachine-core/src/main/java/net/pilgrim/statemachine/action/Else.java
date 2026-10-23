package net.pilgrim.statemachine.action;

import java.util.Collections;
import java.util.List;

public record Else(List<Action> actions) {
    public Else(List<Action> actions) {
        this.actions = actions != null ? List.copyOf(actions) : Collections.emptyList();
    }
}
