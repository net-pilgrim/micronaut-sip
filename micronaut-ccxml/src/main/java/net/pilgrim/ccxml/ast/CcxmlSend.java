package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

/**
 * Represents a &lt;send event="..." target="..." targettype="..." sendid="..." delay="..." namelist="..."/&gt; element.
 */
public record CcxmlSend(
        String event,
        String target,
        String targettype,
        String sendid,
        String delay,
        String namelist
) implements CcxmlExecutable {

    @Override
    public void execute(CcxmlSession session, CcxmlEvent currentEvent) {
        session.send(event, target, targettype, sendid, delay, namelist, currentEvent);
    }
}
