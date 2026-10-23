package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents a &lt;transition event="..." state="..." cond="..."&gt; element in CCXML.
 */
public record CcxmlTransition(
        String event,
        String state,
        String cond,
        List<CcxmlExecutable> executables
) implements CcxmlNode {

    public CcxmlTransition(String event, String state, String cond, List<CcxmlExecutable> executables) {
        this.event = event;
        this.state = state;
        this.cond = cond;
        this.executables = executables != null ? List.copyOf(executables) : Collections.emptyList();
    }

    /**
     * Tests whether this transition matches the current state, event, and condition.
     */
    public boolean matches(String currentState, CcxmlEvent ev, Map<String, Object> variables) {
        // 1. State match
        if (state != null && !state.isBlank()) {
            boolean stateMatched = false;
            String[] tokens = state.trim().split("\\s+");
            for (String s : tokens) {
                if (s.equals(currentState)) {
                    stateMatched = true;
                    break;
                }
            }
            if (!stateMatched) {
                return false;
            }
        }

        // 2. Event match
        if (event != null && !event.isBlank() && !event.equals("*")) {
            String evName = ev != null ? ev.getName() : "";
            boolean eventMatched = false;
            String[] eventTokens = event.trim().split("\\s+");
            for (String pat : eventTokens) {
                if (pat.equals(evName)) {
                    eventMatched = true;
                    break;
                }
                if (pat.endsWith(".*")) {
                    String prefix = pat.substring(0, pat.length() - 2);
                    if (evName.equals(prefix) || evName.startsWith(prefix + ".")) {
                        eventMatched = true;
                        break;
                    }
                } else if (evName.startsWith(pat + ".")) {
                    eventMatched = true;
                    break;
                }
            }
            if (!eventMatched) {
                return false;
            }
        }

        // 3. Condition match
        if (cond != null && !cond.isBlank()) {
            Map<String, Object> eventScope = ev != null ? Map.of("event$", ev) : Collections.emptyMap();
            return CcxmlExpressionEvaluator.evaluateCondition(cond, variables, eventScope);
        }

        return true;
    }

    /**
     * Executes all executables inside this transition.
     */
    public void execute(CcxmlSession session, CcxmlEvent ev) {
        for (CcxmlExecutable exec : executables) {
            exec.execute(session, ev);
            if (session.isTerminated()) {
                break;
            }
        }
    }
}
