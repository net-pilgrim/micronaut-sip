package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents conditional execution blocks &lt;if&gt;, &lt;elseif&gt;, &lt;else&gt; in CCXML.
 */
public record CcxmlIf(
        String cond,
        List<CcxmlExecutable> thenExecutables,
        List<CcxmlElseIf> elseIfs,
        CcxmlElse elseBlock
) implements CcxmlExecutable {

    public CcxmlIf(String cond, List<CcxmlExecutable> thenExecutables, List<CcxmlElseIf> elseIfs, CcxmlElse elseBlock) {
        this.cond = cond;
        this.thenExecutables = thenExecutables != null ? List.copyOf(thenExecutables) : Collections.emptyList();
        this.elseIfs = elseIfs != null ? List.copyOf(elseIfs) : Collections.emptyList();
        this.elseBlock = elseBlock;
    }

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        Map<String, Object> eventScope = Map.of("event$", event);
        if (CcxmlExpressionEvaluator.evaluateCondition(cond, session.getVariables(), eventScope)) {
            for (CcxmlExecutable exec : thenExecutables) {
                exec.execute(session, event);
                if (session.isTerminated()) return;
            }
            return;
        }

        for (CcxmlElseIf elseIf : elseIfs) {
            if (CcxmlExpressionEvaluator.evaluateCondition(elseIf.cond(), session.getVariables(), eventScope)) {
                for (CcxmlExecutable exec : elseIf.executables()) {
                    exec.execute(session, event);
                    if (session.isTerminated()) return;
                }
                return;
            }
        }

        if (elseBlock != null) {
            for (CcxmlExecutable exec : elseBlock.executables()) {
                exec.execute(session, event);
                if (session.isTerminated()) return;
            }
        }
    }
}
