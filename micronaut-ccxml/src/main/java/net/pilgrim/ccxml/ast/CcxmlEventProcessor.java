package net.pilgrim.ccxml.ast;

import java.util.Collections;
import java.util.List;

/**
 * Represents the &lt;eventprocessor statevariable="..."&gt; element in CCXML.
 */
public record CcxmlEventProcessor(
        String statevariable,
        List<CcxmlTransition> transitions
) implements CcxmlNode {

    public CcxmlEventProcessor(String statevariable, List<CcxmlTransition> transitions) {
        this.statevariable = statevariable != null && !statevariable.isBlank() ? statevariable : "current_state";
        this.transitions = transitions != null ? List.copyOf(transitions) : Collections.emptyList();
    }
}
