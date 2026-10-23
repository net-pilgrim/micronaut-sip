package net.pilgrim.ccxml.ast;

import java.util.Collections;
import java.util.List;

/**
 * Represents an &lt;else/&gt; clause inside an &lt;if&gt;.
 */
public record CcxmlElse(List<CcxmlExecutable> executables) implements CcxmlNode {
    public CcxmlElse(List<CcxmlExecutable> executables) {
        this.executables = executables != null ? List.copyOf(executables) : Collections.emptyList();
    }
}
