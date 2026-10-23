package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Map;

/**
 * Represents a &lt;dialogprepare src="..." type="..." dialogid="..."/&gt; element in CCXML.
 */
public record CcxmlDialogPrepare(String src, String type, String dialogid) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        Map<String, Object> eventScope = Map.of("event$", event);
        Object resolvedSrc = CcxmlExpressionEvaluator.evaluate(src, session.getVariables(), eventScope);
        String finalSrc = resolvedSrc != null ? resolvedSrc.toString() : src;

        session.prepareDialog(dialogid, finalSrc, type != null ? type : "application/voicexml+xml");
    }
}
