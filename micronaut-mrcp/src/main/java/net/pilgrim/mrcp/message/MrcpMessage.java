package net.pilgrim.mrcp.message;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Base abstract class for MRCPv2 messages (RFC 6787 §5).
 */
public abstract class MrcpMessage {

    protected MrcpVersion version = MrcpVersion.MRCP_2_0;
    protected long messageLength = 0; // length in octets including start-line to end of body
    protected final MrcpMessageType messageType;
    protected final MrcpHeaders headers;
    protected byte[] body;

    protected MrcpMessage(MrcpMessageType messageType) {
        this.messageType = Objects.requireNonNull(messageType, "messageType must not be null");
        this.headers = new MrcpHeaders();
    }

    protected MrcpMessage(MrcpMessageType messageType, MrcpHeaders headers, byte[] body) {
        this.messageType = Objects.requireNonNull(messageType, "messageType must not be null");
        this.headers = headers != null ? new MrcpHeaders(headers) : new MrcpHeaders();
        this.body = body;
        if (body != null && body.length > 0) {
            this.headers.setContentLength(body.length);
        }
    }

    public MrcpVersion getVersion() {
        return version;
    }

    public void setVersion(MrcpVersion version) {
        this.version = (version != null) ? version : MrcpVersion.MRCP_2_0;
    }

    public long getMessageLength() {
        return messageLength;
    }

    public void setMessageLength(long messageLength) {
        this.messageLength = messageLength;
    }

    public MrcpMessageType getMessageType() {
        return messageType;
    }

    public MrcpHeaders getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
        if (body != null) {
            headers.setContentLength(body.length);
        } else {
            headers.remove(MrcpHeaders.CONTENT_LENGTH);
        }
    }

    public void setBody(String bodyText, String contentType) {
        if (bodyText != null) {
            byte[] bytes = bodyText.getBytes(StandardCharsets.UTF_8);
            setBody(bytes);
            if (contentType != null) {
                headers.setContentType(contentType);
            }
        } else {
            setBody((byte[]) null);
        }
    }

    public String getBodyAsString() {
        return getBodyAsString(StandardCharsets.UTF_8);
    }

    public String getBodyAsString(Charset charset) {
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, charset != null ? charset : StandardCharsets.UTF_8);
    }

    public abstract String getStartLine();
}
