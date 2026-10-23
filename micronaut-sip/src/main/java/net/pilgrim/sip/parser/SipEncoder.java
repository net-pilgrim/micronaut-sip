package net.pilgrim.sip.parser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Encodes SIP Requests and Responses into RFC 3261 formatted byte streams.
 * Supports zero-copy encoding directly into Netty ByteBuf instances.
 */
public class SipEncoder {

    private static final byte[] CRLF = new byte[]{'\r', '\n'};
    private static final byte[] COLON_SPACE = new byte[]{':', ' '};

    public byte[] encode(SipMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Cannot encode null SIP message");
        }

        byte[] body = message.getBody();
        int estimatedSize = 256 + (body != null ? body.length : 0);
        ByteBuf buf = Unpooled.buffer(estimatedSize);
        encode(message, buf);
        return ByteBufUtil.getBytes(buf);
    }

    public void encode(SipMessage message, ByteBuf out) {
        if (message == null) {
            throw new IllegalArgumentException("Cannot encode null SIP message");
        }

        // 1. Start line
        if (message.isRequest()) {
            SipRequest req = (SipRequest) message;
            ByteBufUtil.writeUtf8(out, req.getMethod().name());
            out.writeByte(' ');
            ByteBufUtil.writeUtf8(out, req.getUri().toString());
            out.writeByte(' ');
            ByteBufUtil.writeUtf8(out, req.getSipVersion());
            out.writeBytes(CRLF);
        } else {
            SipResponse res = (SipResponse) message;
            ByteBufUtil.writeUtf8(out, res.getSipVersion());
            out.writeByte(' ');
            ByteBufUtil.writeUtf8(out, String.valueOf(res.getStatusCode()));
            out.writeByte(' ');
            ByteBufUtil.writeUtf8(out, res.getReasonPhrase());
            out.writeBytes(CRLF);
        }

        // 2. Ensure Content-Length header is synchronized
        byte[] body = message.getBody();
        int bodyLen = (body != null) ? body.length : 0;
        message.getHeaders().setContentLength(bodyLen);

        // 3. Headers
        for (Map.Entry<String, List<String>> entry : message.getHeaders().asMap().entrySet()) {
            String headerName = entry.getKey();
            for (String val : entry.getValue()) {
                ByteBufUtil.writeUtf8(out, headerName);
                out.writeBytes(COLON_SPACE);
                ByteBufUtil.writeUtf8(out, val);
                out.writeBytes(CRLF);
            }
        }

        // 4. Header-Body separator
        out.writeBytes(CRLF);

        // 5. Body
        if (bodyLen > 0) {
            out.writeBytes(body);
        }
    }

    public String encodeToString(SipMessage message) {
        return new String(encode(message), StandardCharsets.UTF_8);
    }
}
