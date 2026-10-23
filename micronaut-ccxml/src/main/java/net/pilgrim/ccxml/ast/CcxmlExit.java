package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

/**
 * Represents an &lt;exit expr="..." namelist="..."/&gt; element in CCXML.
 */
public record CcxmlExit(String expr, String namelist) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        session.terminate();
    }
}
