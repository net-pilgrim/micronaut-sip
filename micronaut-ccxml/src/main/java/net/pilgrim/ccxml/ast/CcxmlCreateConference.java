package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Map;

/**
 * Represents a &lt;createconference conferenceid="..." confname="..." reservedtalkers="..."/&gt; element in CCXML.
 */
public record CcxmlCreateConference(
        String conferenceid,
        String confname,
        Integer reservedtalkers
) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        String finalName = confname;
        if (confname != null) {
            Object resolved = CcxmlExpressionEvaluator.evaluate(confname, session.getVariables(), Map.of("event$", event));
            if (resolved != null) {
                finalName = resolved.toString();
            }
        }
        session.createConference(conferenceid, finalName, reservedtalkers);
    }
}
