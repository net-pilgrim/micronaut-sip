package net.pilgrim.ccxml.ast;

import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;

import java.util.Collections;
import java.util.Map;

/**
 * Represents a &lt;dialogstart src="..." type="..." dialogid="..." connectionid="..." conferenceid="..." namelist="..."/&gt; element.
 */
public record CcxmlDialogStart(
        String src,
        String type,
        String dialogid,
        String connectionid,
        String conferenceid,
        String namelist,
        Map<String, String> parameters
) implements CcxmlExecutable {

    public CcxmlDialogStart(String src, String type, String dialogid, String connectionid, String conferenceid, String namelist, Map<String, String> parameters) {
        this.src = src;
        this.type = type != null ? type : "application/voicexml+xml";
        this.dialogid = dialogid;
        this.connectionid = connectionid;
        this.conferenceid = conferenceid;
        this.namelist = namelist;
        this.parameters = parameters != null ? Map.copyOf(parameters) : Collections.emptyMap();
    }

    @Override
    public void execute(CcxmlSession session, CcxmlEvent event) {
        Map<String, Object> eventScope = Map.of("event$", event);
        Object resolvedSrc = CcxmlExpressionEvaluator.evaluate(src, session.getVariables(), eventScope);
        String finalSrc = resolvedSrc != null ? resolvedSrc.toString() : src;

        String finalConnId = null;
        if (connectionid != null && !connectionid.isBlank()) {
            Object resolvedConn = CcxmlExpressionEvaluator.evaluate(connectionid, session.getVariables(), eventScope);
            finalConnId = resolvedConn != null ? resolvedConn.toString() : connectionid;
        } else if (event != null) {
            finalConnId = event.getConnectionId();
        }

        String finalConfId = null;
        if (conferenceid != null && !conferenceid.isBlank()) {
            Object resolvedConf = CcxmlExpressionEvaluator.evaluate(conferenceid, session.getVariables(), eventScope);
            finalConfId = resolvedConf != null ? resolvedConf.toString() : conferenceid;
        } else if (event != null) {
            finalConfId = event.getConferenceId();
        }

        session.startDialog(finalSrc, type, dialogid, finalConnId, finalConfId, namelist, parameters);
    }
}
