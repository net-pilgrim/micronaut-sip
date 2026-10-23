package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Map;

/**
 * Represents a &lt;disconnect connectionid="..." reason="..."/&gt; element in CCXML.
 */
public record CcxmlDisconnect(String connectionid, String reason) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        String connId = null;
        if (connectionid != null && !connectionid.isBlank()) {
            Object resolved = CcxmlExpressionEvaluator.evaluate(connectionid, session.getVariables(), Map.of("event$", event));
            connId = resolved != null ? resolved.toString() : connectionid;
        } else if (event != null) {
            connId = event.getConnectionId();
        }

        session.disconnectCall(connId, reason);
    }
}
