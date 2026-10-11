package net.pilgrim.mrcp.message;

import java.util.Objects;

/**
 * MRCPv2 Request message (RFC 6787 §5.1).
 * <p>
 * Format: {@code MRCP/2.0 <message-length> <method-name> <request-id>}
 */
public class MrcpRequest extends MrcpMessage {

    private final String methodName;
    private final MrcpMethod method;
    private final long requestId;

    public MrcpRequest(MrcpMethod method, long requestId) {
        super(MrcpMessageType.REQUEST);
        this.method = Objects.requireNonNull(method, "method must not be null");
        this.methodName = method.getText();
        this.requestId = requestId;
    }

    public MrcpRequest(String customMethod, long requestId) {
        super(MrcpMessageType.REQUEST);
        this.methodName = Objects.requireNonNull(customMethod, "customMethod must not be null").trim();
        this.method = MrcpMethod.fromString(customMethod);
        this.requestId = requestId;
    }

    public MrcpMethod getMethod() {
        return method;
    }

    public String getMethodName() {
        return methodName;
    }

    public long getRequestId() {
        return requestId;
    }

    /**
     * Creates a matching MRCP response with the specified status code and state.
     * Automatically copies Channel-Identifier header if present.
     */
    public MrcpResponse createResponse(int statusCode, MrcpRequestState state) {
        MrcpResponse resp = new MrcpResponse(requestId, statusCode, state);
        if (headers.contains(MrcpHeaders.CHANNEL_IDENTIFIER)) {
            resp.getHeaders().set(MrcpHeaders.CHANNEL_IDENTIFIER, headers.get(MrcpHeaders.CHANNEL_IDENTIFIER));
        }
        return resp;
    }

    @Override
    public String getStartLine() {
        return version.getValue() + " " + messageLength + " " + methodName + " " + requestId;
    }

    @Override
    public String toString() {
        return "MrcpRequest{" + methodName + " id=" + requestId + '}';
    }
}
