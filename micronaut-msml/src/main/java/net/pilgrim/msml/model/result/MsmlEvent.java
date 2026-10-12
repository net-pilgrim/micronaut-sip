package net.pilgrim.msml.model.result;

import java.util.Collections;
import java.util.Map;

/**
 * Represents an asynchronous MSML media event notification &lt;event&gt; element (RFC 5707 Section 2.3).
 */
public record MsmlEvent(String name, String id, Map<String, String> params) {

    public static final String DIALOG_EXIT = "msml.dialog.exit";
    public static final String PLAY_COMPLETE = "msml.moml.play.complete";
    public static final String RECORD_COMPLETE = "msml.moml.record.complete";
    public static final String DTMF_MATCH = "msml.moml.dtmf.match";
    public static final String CONF_NOMEDIA = "msml.conf.nomedia";

    public MsmlEvent {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Event name must not be blank");
        params = params != null ? Map.copyOf(params) : Collections.emptyMap();
    }

    public static MsmlEvent of(String name, String id) {
        return new MsmlEvent(name, id, Collections.emptyMap());
    }

    public static MsmlEvent of(String name, String id, Map<String, String> params) {
        return new MsmlEvent(name, id, params);
    }

    public static MsmlEvent dialogExit(String id, Map<String, String> params) {
        return new MsmlEvent(DIALOG_EXIT, id, params);
    }

    public static MsmlEvent playComplete(String id) {
        return new MsmlEvent(PLAY_COMPLETE, id, Collections.emptyMap());
    }

    public static MsmlEvent recordComplete(String id, String dest, long bytes, String termChar) {
        return new MsmlEvent(RECORD_COMPLETE, id, Map.of(
                "dest", dest != null ? dest : "",
                "bytes", String.valueOf(bytes),
                "termchar", termChar != null ? termChar : ""
        ));
    }

    public static MsmlEvent dtmfMatch(String id, String digits) {
        return new MsmlEvent(DTMF_MATCH, id, Map.of("digits", digits != null ? digits : ""));
    }
}
