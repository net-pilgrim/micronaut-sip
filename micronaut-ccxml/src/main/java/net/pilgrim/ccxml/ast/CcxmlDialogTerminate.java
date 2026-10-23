package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Map;

/**
 * Represents a &lt;dialogterminate dialogid="..." immediate="..."/&gt; element in CCXML.
 */
public record CcxmlDialogTerminate(String dialogid, boolean immediate) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        String finalDialogId = null;
        if (dialogid != null && !dialogid.isBlank()) {
            Object resolved = CcxmlExpressionEvaluator.evaluate(dialogid, session.getVariables(), Map.of("event$", event));
            finalDialogId = resolved != null ? resolved.toString() : dialogid;
        } else if (event != null) {
            finalDialogId = event.getDialogId();
        }

        session.terminateDialog(finalDialogId, immediate);
    }
}
