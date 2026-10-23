package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Map;

/**
 * Represents an &lt;assign name="..." expr="..."/&gt; element in CCXML.
 */
public record CcxmlAssign(String name, String expr) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        Object val = null;
        if (expr != null && !expr.isBlank()) {
            val = CcxmlExpressionEvaluator.evaluate(expr, session.getVariables(), Map.of("event$", event));
        }
        session.setVariable(name, val);
    }
}
