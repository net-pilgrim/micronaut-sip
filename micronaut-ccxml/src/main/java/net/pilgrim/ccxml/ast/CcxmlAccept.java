package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Map;

/**
 * Represents an &lt;accept connectionid="..." hints="..."/&gt; element in CCXML.
 */
public record CcxmlAccept(String connectionid, String hints) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        String connId = null;
        if (connectionid != null && !connectionid.isBlank()) {
            Object resolved = CcxmlExpressionEvaluator.evaluate(connectionid, session.getVariables(), Map.of("event$", event));
            connId = resolved != null ? resolved.toString() : connectionid;
        } else if (event != null) {
            connId = event.getConnectionId();
        }
        session.acceptCall(connId, hints);
    }
}
