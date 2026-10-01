package net.pilgrim.sip.transport;

import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.parser.SipParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Netty frame decoder for streaming TCP SIP connections (RFC 3261 Section 18.1.1).
 * Accumulates bytes until full SIP headers and Content-Length body octets are received.
 * Also handles TCP keep-alive CRLF ping sequences (RFC 5626).
 */
public class SipStreamFrameDecoder extends ByteToMessageDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(SipStreamFrameDecoder.class);
    private static final Pattern CONTENT_LENGTH_PATTERN = Pattern.compile("(?im)^(?:Content-Length|l)\\s*:\\s*(\\d+)");

    private final SipParser parser;

    public SipStreamFrameDecoder(SipParser parser) {
        this.parser = parser;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 1. Skip any leading CRLF/LF keep-alive pings
        while (in.isReadable()) {
            byte b = in.getByte(in.readerIndex());
            if (b == '\r' || b == '\n') {
                in.readByte();
            } else {
                break;
            }
        }

        if (in.readableBytes() == 0) {
            return;
        }

        int readerIndex = in.readerIndex();
        int readableBytes = in.readableBytes();

        // 2. Search for header-body delimiter (\r\n\r\n or \n\n)
        int headerEnd = -1;
        int delimiterLength = 0;

        for (int i = readerIndex; i < readerIndex + readableBytes - 1; i++) {
            if (i + 3 < readerIndex + readableBytes &&
                    in.getByte(i) == '\r' && in.getByte(i + 1) == '\n' &&
                    in.getByte(i + 2) == '\r' && in.getByte(i + 3) == '\n') {
                headerEnd = i;
                delimiterLength = 4;
                break;
            } else if (in.getByte(i) == '\n' && in.getByte(i + 1) == '\n') {
                headerEnd = i;
                delimiterLength = 2;
                break;
            }
        }

        if (headerEnd == -1) {
            // Header block incomplete, wait for more chunks
            return;
        }

        int headerLength = headerEnd - readerIndex;
        byte[] headerBytes = new byte[headerLength];
        in.getBytes(readerIndex, headerBytes);
        String headerText = new String(headerBytes, StandardCharsets.UTF_8);

        // 3. Extract Content-Length
        int contentLength = extractContentLength(headerText);
        int totalMessageLength = headerLength + delimiterLength + contentLength;

        if (totalMessageLength > parser.getMaxMessageSizeBytes()) {
            LOG.warn("Rejecting oversized TCP SIP frame ({} bytes > {} max) from {}",
                    totalMessageLength, parser.getMaxMessageSizeBytes(), ctx.channel().remoteAddress());
            ctx.close();
            return;
        }

        if (readableBytes < totalMessageLength) {
            // Body bytes not fully received yet, wait for more chunks
            return;
        }

        // 4. We have the complete SIP message!
        byte[] fullMessageBytes = new byte[totalMessageLength];
        in.readBytes(fullMessageBytes);

        try {
            InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
            SipMessage sipMessage = parser.parse(fullMessageBytes, remoteAddress);
            sipMessage.setTransport(SipTransport.TCP);
            out.add(sipMessage);
        } catch (Exception e) {
            LOG.warn("Failed to parse TCP SIP message from {}: {}", ctx.channel().remoteAddress(), e.getMessage());
        }
    }

    private int extractContentLength(String headerText) {
        Matcher matcher = CONTENT_LENGTH_PATTERN.matcher(headerText);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1).trim());
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
