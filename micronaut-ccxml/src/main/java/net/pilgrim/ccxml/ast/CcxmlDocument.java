package net.pilgrim.ccxml.ast;

import java.util.Collections;
import java.util.List;

/**
 * Root AST node for a CCXML 1.0 document.
 */
public record CcxmlDocument(
        String version,
        List<CcxmlVar> vars,
        List<CcxmlScript> scripts,
        CcxmlEventProcessor eventProcessor
) implements CcxmlNode {

    public CcxmlDocument(String version, List<CcxmlVar> vars, List<CcxmlScript> scripts, CcxmlEventProcessor eventProcessor) {
        this.version = version != null ? version : "1.0";
        this.vars = vars != null ? List.copyOf(vars) : Collections.emptyList();
        this.scripts = scripts != null ? List.copyOf(scripts) : Collections.emptyList();
        this.eventProcessor = eventProcessor != null ? eventProcessor : new CcxmlEventProcessor("current_state", Collections.emptyList());
    }
}
