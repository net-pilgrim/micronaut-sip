package net.pilgrim.mrcp.codec;

import net.pilgrim.mrcp.message.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Serializes {@link MrcpMessage} instances into RFC 6787 stream wire format.
 */
public final class MrcpMessageSerializer {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private MrcpMessageSerializer() {}

    /**
     * Serializes an MRCP message into bytes according to RFC 6787,
     * computing and updating {@code messageLength} in the start line.
     */
    public static byte[] serialize(MrcpMessage message) {
        if (message == null) {
            return new byte[0];
        }

        // 1. Prepare header block and body
        byte[] body = message.getBody();
        if (body != null && body.length > 0) {
            message.getHeaders().setContentLength(body.length);
        }

        StringBuilder headerBuilder = new StringBuilder();
        for (Map.Entry<String, String> header : message.getHeaders()) {
            headerBuilder.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        headerBuilder.append("\r\n");
        byte[] headersBytes = headerBuilder.toString().getBytes(StandardCharsets.UTF_8);

        // 2. Prepare start-line tokens without message-length
        String prefix = message.getVersion().getValue() + " ";
        String suffix;

        if (message instanceof MrcpRequest req) {
            suffix = " " + req.getMethodName() + " " + req.getRequestId() + "\r\n";
        } else if (message instanceof MrcpResponse resp) {
            suffix = " " + resp.getRequestId() + " " + resp.getStatusCode() + " " + resp.getRequestState().getText() + "\r\n";
        } else if (message instanceof MrcpEvent event) {
            suffix = " " + event.getEventName() + " " + event.getRequestId() + " " + event.getRequestState().getText() + "\r\n";
        } else {
            throw new IllegalArgumentException("Unknown message class: " + message.getClass());
        }

        byte[] prefixBytes = prefix.getBytes(StandardCharsets.US_ASCII);
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.US_ASCII);

        int bodyLen = (body != null) ? body.length : 0;
        int fixedOctets = prefixBytes.length + suffixBytes.length + headersBytes.length + bodyLen;

        // 3. Iteratively solve length L = fixedOctets + digits(L)
        int digits = String.valueOf(fixedOctets).length();
        long totalLength = fixedOctets + digits;
        while (String.valueOf(totalLength).length() != digits) {
            digits = String.valueOf(totalLength).length();
            totalLength = fixedOctets + digits;
        }

        message.setMessageLength(totalLength);
        byte[] lengthBytes = String.valueOf(totalLength).getBytes(StandardCharsets.US_ASCII);

        // 4. Assemble the complete message
        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) totalLength);
        try {
            baos.write(prefixBytes);
            baos.write(lengthBytes);
            baos.write(suffixBytes);
            baos.write(headersBytes);
            if (body != null && body.length > 0) {
                baos.write(body);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize MRCP message", e);
        }

        return baos.toByteArray();
    }

    public static String serializeToString(MrcpMessage message) {
        return new String(serialize(message), StandardCharsets.UTF_8);
    }
}
