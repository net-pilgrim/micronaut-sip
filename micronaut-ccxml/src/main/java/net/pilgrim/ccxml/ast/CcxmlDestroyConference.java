package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Map;

/**
 * Represents a &lt;destroyconference conferenceid="..."/&gt; element in CCXML.
 */
public record CcxmlDestroyConference(String conferenceid) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        String confId = null;
        if (conferenceid != null && !conferenceid.isBlank()) {
            Object resolved = CcxmlExpressionEvaluator.evaluate(conferenceid, session.getVariables(), Map.of("event$", event));
            confId = resolved != null ? resolved.toString() : conferenceid;
        } else if (event != null) {
            confId = event.getConferenceId();
        }
        session.destroyConference(confId);
    }
}
