package net.pilgrim.sip.parser;

import net.pilgrim.sip.model.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * High-performance, RFC 3261 compliant SIP message parser.
 * Handles header folding, compact header names, status lines, request lines, and body extraction.
 */
public class SipParser {

    private final int maxMessageSizeBytes;
    private final int maxHeaderCount;
    private final int maxHeaderSizeBytes;

    public SipParser() {
        this(65536, 100, 8192);
    }

    public SipParser(int maxMessageSizeBytes, int maxHeaderCount, int maxHeaderSizeBytes) {
        this.maxMessageSizeBytes = maxMessageSizeBytes > 0 ? maxMessageSizeBytes : 65536;
        this.maxHeaderCount = maxHeaderCount > 0 ? maxHeaderCount : 100;
        this.maxHeaderSizeBytes = maxHeaderSizeBytes > 0 ? maxHeaderSizeBytes : 8192;
    }

    public int getMaxMessageSizeBytes() {
        return maxMessageSizeBytes;
    }

    public int getMaxHeaderCount() {
        return maxHeaderCount;
    }

    public int getMaxHeaderSizeBytes() {
        return maxHeaderSizeBytes;
    }

    public SipMessage parse(byte[] bytes) {
        return parse(bytes, null);
    }

    public SipMessage parse(byte[] bytes, InetSocketAddress remoteAddress) {
        if (bytes == null || bytes.length == 0) {
            throw new SipParseException("Cannot parse empty SIP message bytes");
        }
        if (bytes.length > maxMessageSizeBytes) {
            throw new SipMessageTooLargeException("SIP message size (" + bytes.length + " bytes) exceeds limit of " + maxMessageSizeBytes + " bytes");
        }

        // Find boundary between headers and body (\r\n\r\n or \n\n)
        int bodyStartOffset = -1;
        int delimiterLength = 0;

        for (int i = 0; i < bytes.length; i++) {
            if (i + 3 < bytes.length && bytes[i] == '\r' && bytes[i + 1] == '\n' && bytes[i + 2] == '\r' && bytes[i + 3] == '\n') {
                bodyStartOffset = i + 4;
                delimiterLength = 4;
                break;
            } else if (i + 1 < bytes.length && bytes[i] == '\n' && bytes[i + 1] == '\n') {
                bodyStartOffset = i + 2;
                delimiterLength = 2;
                break;
            }
        }

        byte[] headerBytes;
        byte[] rawBodyBytes;

        if (bodyStartOffset != -1) {
            headerBytes = Arrays.copyOfRange(bytes, 0, bodyStartOffset - delimiterLength);
            rawBodyBytes = Arrays.copyOfRange(bytes, bodyStartOffset, bytes.length);
        } else {
            headerBytes = bytes;
            rawBodyBytes = new byte[0];
        }

        String headerText = new String(headerBytes, StandardCharsets.UTF_8);
        List<String> rawLines = splitLines(headerText);

        // Skip leading blank lines (RFC 3261 Section 7.3.1)
        int startIdx = 0;
        while (startIdx < rawLines.size() && rawLines.get(startIdx).trim().isEmpty()) {
            startIdx++;
        }

        if (startIdx >= rawLines.size()) {
            throw new SipParseException("SIP message contains no content");
        }

        String startLine = rawLines.get(startIdx).trim();

        // Unfold remaining header lines (RFC 3261 Section 7.3.1)
        List<String> unfoldedHeaders = new ArrayList<>();
        StringBuilder currentHeader = null;

        for (int i = startIdx + 1; i < rawLines.size(); i++) {
            String line = rawLines.get(i);
            if (line.isEmpty()) continue;

            char firstChar = line.charAt(0);
            if ((firstChar == ' ' || firstChar == '\t') && currentHeader != null) {
                // Continuation line
                currentHeader.append(" ").append(line.trim());
            } else {
                if (currentHeader != null) {
                    unfoldedHeaders.add(currentHeader.toString());
                }
                currentHeader = new StringBuilder(line.trim());
            }
        }
        if (currentHeader != null && !currentHeader.isEmpty()) {
            unfoldedHeaders.add(currentHeader.toString());
        }

        if (unfoldedHeaders.isEmpty()) {
            throw new SipParseException("SIP message contains no headers");
        }

        // Determine if Request or Response
        SipMessage message;
        if (startLine.toUpperCase().startsWith("SIP/2.0 ")) {
            message = parseStatusLine(startLine);
        } else {
            message = parseRequestLine(startLine);
        }

        if (remoteAddress != null) {
            message.setRemoteAddress(remoteAddress);
        }

        if (unfoldedHeaders.size() > maxHeaderCount) {
            throw new SipParseException("SIP message contains too many headers (" + unfoldedHeaders.size() + " > " + maxHeaderCount + ")");
        }

        // Parse headers
        for (String h : unfoldedHeaders) {
            if (h.length() > maxHeaderSizeBytes) {
                throw new SipParseException("SIP header length (" + h.length() + ") exceeds limit of " + maxHeaderSizeBytes + " bytes");
            }
            int colon = h.indexOf(':');
            if (colon == -1) {
                throw new SipParseException("Malformed header line (missing ':'): " + h);
            }
            String name = h.substring(0, colon).trim();
            String value = h.substring(colon + 1).trim();
            message.getHeaders().add(name, value);
        }

        if (remoteAddress != null && message instanceof SipRequest req) {
            req.processNatVia(remoteAddress);
        }

        // Validate and process body according to Content-Length
        if (message.getHeaders().contains(SipHeaders.CONTENT_LENGTH)) {
            String clStr = message.getHeaders().get(SipHeaders.CONTENT_LENGTH);
            if (clStr != null) {
                try {
                    int parsedCl = Integer.parseInt(clStr.trim());
                    if (parsedCl < 0) {
                        throw new SipParseException("Negative Content-Length: " + parsedCl);
                    }
                } catch (NumberFormatException e) {
                    throw new SipParseException("Invalid non-numeric Content-Length: " + clStr);
                }
            }
        }

        int contentLength = message.getHeaders().getContentLength();
        if (contentLength > 0) {
            int toCopy = Math.min(contentLength, rawBodyBytes.length);
            message.setBody(Arrays.copyOfRange(rawBodyBytes, 0, toCopy));
        } else if (rawBodyBytes.length > 0 && message.getHeaders().contains(SipHeaders.CONTENT_LENGTH)) {
            // Explicit Content-Length: 0
            message.setBody(new byte[0]);
        } else if (rawBodyBytes.length > 0) {
            // UDP with omitted Content-Length: whole payload is body
            message.setBody(rawBodyBytes);
        }

        return message;
    }

    public SipMessage parse(String message) {
        return parse(message, null);
    }

    public SipMessage parse(String message, InetSocketAddress remoteAddress) {
        if (message == null) {
            throw new SipParseException("Cannot parse null SIP message");
        }
        return parse(message.getBytes(StandardCharsets.UTF_8), remoteAddress);
    }

    private SipResponse parseStatusLine(String line) {
        // e.g. "SIP/2.0 200 OK" or "SIP/2.0 180 Ringing"
        String[] parts = line.split("\\s+", 3);
        if (parts.length < 2) {
            throw new SipParseException("Invalid SIP status line: " + line);
        }
        String version = parts[0];
        int statusCode;
        try {
            statusCode = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new SipParseException("Invalid status code in status line: " + line);
        }
        String reasonPhrase = parts.length >= 3 ? parts[2].trim() : SipStatus.getReasonPhrase(statusCode);

        SipResponse response = new SipResponse(statusCode, reasonPhrase);
        response.setSipVersion(version);
        return response;
    }

    private SipRequest parseRequestLine(String line) {
        // e.g. "INVITE sip:bob@example.com SIP/2.0"
        String[] parts = line.split("\\s+");
        if (parts.length < 3) {
            throw new SipParseException("Invalid SIP request line: " + line);
        }
        String methodStr = parts[0];
        String uriStr = parts[1];
        String version = parts[2];

        SipMethod method;
        try {
            method = SipMethod.from(methodStr);
        } catch (Exception e) {
            throw new SipParseException("Unsupported SIP method: " + methodStr, e);
        }

        SipUri uri = SipUri.parse(uriStr);
        SipRequest request = new SipRequest(method, uri);
        request.setSipVersion(version);
        return request;
    }

    private List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int len = text.length();
        int start = 0;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < len && text.charAt(i + 1) == '\n') {
                    lines.add(text.substring(start, i));
                    i++;
                    start = i + 1;
                } else {
                    lines.add(text.substring(start, i));
                    start = i + 1;
                }
            } else if (c == '\n') {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (start < len) {
            lines.add(text.substring(start));
        }
        return lines;
    }
}
