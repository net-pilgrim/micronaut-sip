package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Map;

/**
 * Represents a &lt;reject connectionid="..." reason="..." hints="..."/&gt; element in CCXML.
 */
public record CcxmlReject(String connectionid, String reason, int statusCode, String hints) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        String connId = null;
        if (connectionid != null && !connectionid.isBlank()) {
            Object resolved = CcxmlExpressionEvaluator.evaluate(connectionid, session.getVariables(), Map.of("event$", event));
            connId = resolved != null ? resolved.toString() : connectionid;
        } else if (event != null) {
            connId = event.getConnectionId();
        }
        session.rejectCall(connId, statusCode > 0 ? statusCode : 486, reason != null ? reason : "Busy Here");
    }
}
