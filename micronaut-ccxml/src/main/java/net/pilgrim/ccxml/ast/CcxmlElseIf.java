package net.pilgrim.ccxml.ast;

import java.util.Collections;
import java.util.List;

/**
 * Represents an &lt;elseif cond="..."/&gt; clause inside an &lt;if&gt;.
 */
public record CcxmlElseIf(String cond, List<CcxmlExecutable> executables) implements CcxmlNode {
    public CcxmlElseIf(String cond, List<CcxmlExecutable> executables) {
        this.cond = cond;
        this.executables = executables != null ? List.copyOf(executables) : Collections.emptyList();
    }
}
