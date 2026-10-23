package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

/**
 * Represents a &lt;script&gt; element in CCXML.
 */
public record CcxmlScript(String content, String src) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        if (content != null && !content.isBlank()) {
            session.executeScript(content, event);
        }
    }
}
