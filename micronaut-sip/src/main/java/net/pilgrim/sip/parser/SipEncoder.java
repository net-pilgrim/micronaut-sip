package net.pilgrim.sip.parser;

import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Encodes SIP Requests and Responses into RFC 3261 formatted byte streams.
 */
public class SipEncoder {

    public byte[] encode(SipMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Cannot encode null SIP message");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // 1. Start line
            if (message.isRequest()) {
                SipRequest req = (SipRequest) message;
                String startLine = req.getMethod().name() + " " + req.getUri().toString() + " " + req.getSipVersion() + "\r\n";
                out.write(startLine.getBytes(StandardCharsets.UTF_8));
            } else {
                SipResponse res = (SipResponse) message;
                String startLine = res.getSipVersion() + " " + res.getStatusCode() + " " + res.getReasonPhrase() + "\r\n";
                out.write(startLine.getBytes(StandardCharsets.UTF_8));
            }

            // 2. Ensure Content-Length header is synchronized
            byte[] body = message.getBody();
            int bodyLen = (body != null) ? body.length : 0;
            message.getHeaders().setContentLength(bodyLen);

            // 3. Headers
            for (Map.Entry<String, List<String>> entry : message.getHeaders().asMap().entrySet()) {
                String headerName = entry.getKey();
                for (String val : entry.getValue()) {
                    String line = headerName + ": " + val + "\r\n";
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                }
            }

            // 4. Header-Body separator
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));

            // 5. Body
            if (bodyLen > 0) {
                out.write(body);
            }

            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode SIP message", e);
        }
    }

    public String encodeToString(SipMessage message) {
        return new String(encode(message), StandardCharsets.UTF_8);
    }
}
