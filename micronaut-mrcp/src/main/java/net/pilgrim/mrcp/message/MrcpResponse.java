package net.pilgrim.mrcp.message;

import java.util.Objects;

/**
 * MRCPv2 Response message (RFC 6787 §5.2).
 * <p>
 * Format: {@code MRCP/2.0 <message-length> <request-id> <status-code> <request-state>}
 */
public class MrcpResponse extends MrcpMessage {

    private final long requestId;
    private final int statusCode;
    private final MrcpRequestState requestState;

    public MrcpResponse(long requestId, int statusCode, MrcpRequestState requestState) {
        super(MrcpMessageType.RESPONSE);
        this.requestId = requestId;
        this.statusCode = statusCode;
        this.requestState = Objects.requireNonNull(requestState, "requestState must not be null");
    }

    public long getRequestId() {
        return requestId;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public MrcpRequestState getRequestState() {
        return requestState;
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    @Override
    public String getStartLine() {
        return version.getValue() + " " + messageLength + " " + requestId + " " + statusCode + " " + requestState.getText();
    }

    @Override
    public String toString() {
        return "MrcpResponse{id=" + requestId + " status=" + statusCode + " state=" + requestState + '}';
    }
}
