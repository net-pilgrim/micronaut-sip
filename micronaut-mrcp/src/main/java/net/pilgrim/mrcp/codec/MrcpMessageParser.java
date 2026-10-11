package net.pilgrim.mrcp.codec;

import net.pilgrim.mrcp.message.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Parser for MRCPv2 messages (RFC 6787 §5).
 */
public final class MrcpMessageParser {

    private MrcpMessageParser() {}

    /**
     * Parses a complete MRCP message from byte array.
     */
    public static MrcpMessage parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Cannot parse empty byte array as MRCP message");
        }
        return parse(bytes, 0, bytes.length);
    }

    /**
     * Parses a complete MRCP message from a slice of byte array.
     */
    public static MrcpMessage parse(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0) {
            throw new IllegalArgumentException("Cannot parse empty byte slice as MRCP message");
        }

        // Find header end marker "\r\n\r\n" or "\n\n"
        int headerEnd = -1;
        int crlfLen = 4;
        for (int i = offset; i <= offset + length - 4; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n' && bytes[i + 2] == '\r' && bytes[i + 3] == '\n') {
                headerEnd = i;
                crlfLen = 4;
                break;
            }
        }
        if (headerEnd == -1) {
            for (int i = offset; i <= offset + length - 2; i++) {
                if (bytes[i] == '\n' && bytes[i + 1] == '\n') {
                    headerEnd = i;
                    crlfLen = 2;
                    break;
                }
            }
        }

        if (headerEnd == -1) {
            throw new IllegalArgumentException("Invalid MRCP message: could not find end of headers marker (CRLF CRLF)");
        }

        String headerBlock = new String(bytes, offset, headerEnd - offset, StandardCharsets.UTF_8);
        String[] lines = headerBlock.split("\r?\n");
        if (lines.length == 0) {
            throw new IllegalArgumentException("Missing MRCP start line");
        }

        String startLine = lines[0].trim();
        String[] tokens = startLine.split("\\s+");
        if (tokens.length < 4) {
            throw new IllegalArgumentException("Invalid MRCP start-line: " + startLine);
        }

        MrcpVersion version = MrcpVersion.fromString(tokens[0]);
        long messageLength;
        try {
            messageLength = Long.parseLong(tokens[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid MRCP message length: " + tokens[1], e);
        }

        MrcpMessage message;

        if (tokens.length == 4) {
            // Request: MRCP/2.0 <length> <method> <request-id>
            String methodName = tokens[2];
            long requestId = Long.parseLong(tokens[3]);
            message = new MrcpRequest(methodName, requestId);
        } else {
            // 5 tokens: either Response or Event
            // Response: MRCP/2.0 <length> <request-id> <status-code> <request-state>
            // Event:    MRCP/2.0 <length> <event-name> <request-id> <request-state>
            boolean isResponse = false;
            long responseReqId = 0;
            int statusCode = 0;

            try {
                responseReqId = Long.parseLong(tokens[2]);
                statusCode = Integer.parseInt(tokens[3]);
                isResponse = true;
            } catch (NumberFormatException ignored) {}

            if (isResponse) {
                MrcpRequestState state = MrcpRequestState.fromString(tokens[4]);
                message = new MrcpResponse(responseReqId, statusCode, state);
            } else {
                String eventName = tokens[2];
                long requestId = Long.parseLong(tokens[3]);
                MrcpRequestState state = MrcpRequestState.fromString(tokens[4]);
                message = new MrcpEvent(eventName, requestId, state);
            }
        }

        message.setVersion(version);
        message.setMessageLength(messageLength);

        // Parse headers
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String headerName = line.substring(0, colonIdx).trim();
                String headerVal = line.substring(colonIdx + 1).trim();
                message.getHeaders().add(headerName, headerVal);
            }
        }

        // Parse body if present
        int bodyStart = headerEnd + crlfLen;
        int bodyLength = offset + length - bodyStart;
        int contentLength = message.getHeaders().getContentLength();

        if (contentLength > 0 && bodyLength >= contentLength) {
            byte[] bodyBytes = Arrays.copyOfRange(bytes, bodyStart, bodyStart + contentLength);
            message.setBody(bodyBytes);
        } else if (bodyLength > 0) {
            byte[] bodyBytes = Arrays.copyOfRange(bytes, bodyStart, bodyStart + bodyLength);
            message.setBody(bodyBytes);
        }

        return message;
    }

    public static MrcpMessage parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Cannot parse null text as MRCP message");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return parse(bytes);
    }
}
