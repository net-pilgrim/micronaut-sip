package net.pilgrim.msml.model;

import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;

import java.util.List;

/**
 * Root MSML document container (&lt;msml version="1.1"&gt;) according to RFC 5707 Section 2.
 */
public record MsmlDocument(
        String version,
        List<MsmlCommand> commands,
        List<MsmlResult> results,
        List<MsmlEvent> events
) {

    public static final String DEFAULT_VERSION = "1.1";
    public static final String CONTENT_TYPE = "application/msml+xml";

    public MsmlDocument {
        if (version == null || version.isBlank()) version = DEFAULT_VERSION;
        commands = commands != null ? List.copyOf(commands) : List.of();
        results = results != null ? List.copyOf(results) : List.of();
        events = events != null ? List.copyOf(events) : List.of();
    }

    public static MsmlDocument ofCommands(List<MsmlCommand> commands) {
        return new MsmlDocument(DEFAULT_VERSION, commands, List.of(), List.of());
    }

    public static MsmlDocument ofCommand(MsmlCommand command) {
        return new MsmlDocument(DEFAULT_VERSION, List.of(command), List.of(), List.of());
    }

    public static MsmlDocument ofResults(List<MsmlResult> results) {
        return new MsmlDocument(DEFAULT_VERSION, List.of(), results, List.of());
    }

    public static MsmlDocument ofResult(MsmlResult result) {
        return new MsmlDocument(DEFAULT_VERSION, List.of(), List.of(result), List.of());
    }

    public static MsmlDocument ofEvents(List<MsmlEvent> events) {
        return new MsmlDocument(DEFAULT_VERSION, List.of(), List.of(), events);
    }

    public static MsmlDocument ofEvent(MsmlEvent event) {
        return new MsmlDocument(DEFAULT_VERSION, List.of(), List.of(), List.of(event));
    }

    public boolean isCommand() {
        return !commands.isEmpty();
    }

    public boolean isResult() {
        return !results.isEmpty();
    }

    public boolean isEvent() {
        return !events.isEmpty();
    }
}
