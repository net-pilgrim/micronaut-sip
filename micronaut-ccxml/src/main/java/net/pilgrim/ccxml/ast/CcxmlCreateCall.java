package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Map;

/**
 * Represents a &lt;createcall dest="..." callerid="..." connectionid="..." timeout="..." aai="..."/&gt; element.
 */
public record CcxmlCreateCall(
        String dest,
        String callerid,
        String connectionid,
        String timeout,
        String aai
) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        Map<String, Object> eventScope = Map.of("event$", event);
        Object resolvedDest = CcxmlExpressionEvaluator.evaluate(dest, session.getVariables(), eventScope);
        String finalDest = resolvedDest != null ? resolvedDest.toString() : dest;

        String finalCallerId = null;
        if (callerid != null && !callerid.isBlank()) {
            Object resolvedCaller = CcxmlExpressionEvaluator.evaluate(callerid, session.getVariables(), eventScope);
            finalCallerId = resolvedCaller != null ? resolvedCaller.toString() : callerid;
        }

        session.createCall(finalDest, finalCallerId, timeout, connectionid, aai);
    }
}
