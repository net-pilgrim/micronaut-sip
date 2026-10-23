package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

/**
 * Represents a &lt;cancel sendid="..."/&gt; element in CCXML.
 */
public record CcxmlCancel(String sendid) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        if (sendid != null) {
            session.cancelScheduledEvent(sendid);
        }
    }
}
