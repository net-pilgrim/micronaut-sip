package net.pilgrim.sip.bdd.model;

import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe recorder of SIP message flow across all virtual actors and SUT.
 * Can export Mermaid sequence diagrams for scenario visual reporting.
 */
public class CallLadderRecorder {

    private final List<RecordedMessage> events = new CopyOnWriteArrayList<>();

    public void record(RecordedMessage message) {
        events.add(message);
    }

    public List<RecordedMessage> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public void clear() {
        events.clear();
    }

    /**
     * Generates a GitHub-compatible Mermaid sequence diagram representing the captured call ladder.
     */
    public String generateMermaidDiagram() {
        if (events.isEmpty()) {
            return "```mermaid\nsequenceDiagram\n    Note over SUT: No SIP messages exchanged\n```";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\nsequenceDiagram\n");
        sb.append("    autonumber\n");

        for (RecordedMessage event : events) {
            String actor = sanitizeParticipant(event.actorName());
            String peer = sanitizeParticipant(event.peerName() != null ? event.peerName() : "SUT");

            String sender;
            String receiver;
            String arrow;

            if (event.direction() == RecordedMessage.Direction.SENT) {
                sender = actor;
                receiver = peer;
                arrow = (event.message() instanceof SipResponse) ? "-->>" : "->>";
            } else {
                sender = peer;
                receiver = actor;
                arrow = (event.message() instanceof SipResponse) ? "-->>" : "->>";
            }

            String summary = event.summary().replace(":", " ");
            sb.append("    ")
              .append(sender)
              .append(arrow)
              .append(receiver)
              .append(": ")
              .append(summary)
              .append("\n");
        }

        sb.append("```\n");
        return sb.toString();
    }

    private String sanitizeParticipant(String name) {
        if (name == null || name.isBlank()) {
            return "Endpoint";
        }
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
