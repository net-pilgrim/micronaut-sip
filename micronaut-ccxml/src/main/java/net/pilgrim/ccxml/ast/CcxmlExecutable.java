package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

/**
 * Interface implemented by executable elements in CCXML (actions executed within transitions and blocks).
 */
public interface CcxmlExecutable extends CcxmlNode {

    /**
     * Executes this CCXML action within the context of a session and current event.
     *
     * @param session the active CCXML session
     * @param event the event triggering the transition
     */
    void execute(CcxmlSession session, CcxmlEvent event);
}
