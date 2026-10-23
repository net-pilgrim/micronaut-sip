package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Map;

/**
 * Represents a &lt;redirect connectionid="..." dest="..." reason="..."/&gt; element.
 */
public record CcxmlRedirect(String connectionid, String dest, String reason) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        String connId = connectionid != null && !connectionid.isBlank()
                ? connectionid
                : (event != null ? event.getConnectionId() : null);

        Map<String, Object> eventScope = Map.of("event$", event);
        Object resolvedDest = CcxmlExpressionEvaluator.evaluate(dest, session.getVariables(), eventScope);
        String finalDest = resolvedDest != null ? resolvedDest.toString() : dest;

        session.redirectCall(connId, finalDest, reason);
    }
}
