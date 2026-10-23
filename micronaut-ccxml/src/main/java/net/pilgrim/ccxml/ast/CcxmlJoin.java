package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.bridge.DuplexMode;
import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Map;

/**
 * Represents a &lt;join id1="..." id2="..." duplex="..."/&gt; element in CCXML.
 */
public record CcxmlJoin(String id1, String id2, DuplexMode duplex) implements CcxmlExecutable {

    public CcxmlJoin(String id1, String id2, DuplexMode duplex) {
        this.id1 = id1;
        this.id2 = id2;
        this.duplex = duplex != null ? duplex : DuplexMode.FULL;
    }

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        Map<String, Object> eventScope = Map.of("event$", event);

        Object r1 = CcxmlExpressionEvaluator.evaluate(id1, session.getVariables(), eventScope);
        String finalId1 = r1 != null ? r1.toString() : id1;

        Object r2 = CcxmlExpressionEvaluator.evaluate(id2, session.getVariables(), eventScope);
        String finalId2 = r2 != null ? r2.toString() : id2;

        session.join(finalId1, finalId2, duplex);
    }
}
