package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Map;

/**
 * Represents an &lt;unjoin id1="..." id2="..."/&gt; element in CCXML.
 */
public record CcxmlUnjoin(String id1, String id2) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        Map<String, Object> eventScope = Map.of("event$", event);

        Object r1 = CcxmlExpressionEvaluator.evaluate(id1, session.getVariables(), eventScope);
        String finalId1 = r1 != null ? r1.toString() : id1;

        Object r2 = CcxmlExpressionEvaluator.evaluate(id2, session.getVariables(), eventScope);
        String finalId2 = r2 != null ? r2.toString() : id2;

        session.unjoin(finalId1, finalId2);
    }
}
