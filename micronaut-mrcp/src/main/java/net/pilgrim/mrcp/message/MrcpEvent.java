package net.pilgrim.mrcp.message;

import java.util.Objects;

/**
 * MRCPv2 Event message (RFC 6787 §5.3).
 * <p>
 * Format: {@code MRCP/2.0 <message-length> <event-name> <request-id> <request-state>}
 */
public class MrcpEvent extends MrcpMessage {

    private final String eventName;
    private final MrcpEventName event;
    private final long requestId;
    private final MrcpRequestState requestState;

    public MrcpEvent(MrcpEventName event, long requestId, MrcpRequestState requestState) {
        super(MrcpMessageType.EVENT);
        this.event = Objects.requireNonNull(event, "event must not be null");
        this.eventName = event.getText();
        this.requestId = requestId;
        this.requestState = Objects.requireNonNull(requestState, "requestState must not be null");
    }

    public MrcpEvent(String customEventName, long requestId, MrcpRequestState requestState) {
        super(MrcpMessageType.EVENT);
        this.eventName = Objects.requireNonNull(customEventName, "customEventName must not be null").trim();
        this.event = MrcpEventName.fromString(customEventName);
        this.requestId = requestId;
        this.requestState = Objects.requireNonNull(requestState, "requestState must not be null");
    }

    public MrcpEventName getEvent() {
        return event;
    }

    public String getEventName() {
        return eventName;
    }

    public long getRequestId() {
        return requestId;
    }

    public MrcpRequestState getRequestState() {
        return requestState;
    }

    @Override
    public String getStartLine() {
        return version.getValue() + " " + messageLength + " " + eventName + " " + requestId + " " + requestState.getText();
    }

    @Override
    public String toString() {
        return "MrcpEvent{" + eventName + " id=" + requestId + " state=" + requestState + '}';
    }
}
