package net.pilgrim.mrcp.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import net.pilgrim.mrcp.message.MrcpMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Netty {@link ByteToMessageDecoder} for framing and decoding MRCPv2 messages over TCP.
 * <p>
 * Inspects the {@code <message-length>} in the start line to determine message bounds.
 */
public class MrcpNettyDecoder extends ByteToMessageDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(MrcpNettyDecoder.class);
    private static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024; // 16 MB cap

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 16) {
            return;
        }

        // Find end of first line to extract message-length
        int readerIndex = in.readerIndex();
        int lineEnd = -1;
        for (int i = readerIndex; i < in.writerIndex() - 1; i++) {
            if (in.getByte(i) == '\r' && in.getByte(i + 1) == '\n') {
                lineEnd = i;
                break;
            } else if (in.getByte(i) == '\n') {
                lineEnd = i;
                break;
            }
        }

        if (lineEnd == -1) {
            // Full start line not arrived yet
            return;
        }

        // Read start line string
        int lineLen = lineEnd - readerIndex;
        byte[] lineBytes = new byte[lineLen];
        in.getBytes(readerIndex, lineBytes);
        String startLine = new String(lineBytes, StandardCharsets.US_ASCII).trim();

        String[] tokens = startLine.split("\\s+");
        if (tokens.length < 2 || !tokens[0].toUpperCase(java.util.Locale.ROOT).startsWith("MRCP/")) {
            LOG.warn("Non-MRCP protocol line received: '{}', closing channel", startLine);
            in.skipBytes(in.readableBytes());
            ctx.close();
            return;
        }

        long messageLength;
        try {
            messageLength = Long.parseLong(tokens[1]);
        } catch (NumberFormatException e) {
            LOG.warn("Invalid MRCP message-length in start-line: '{}', closing channel", startLine);
            in.skipBytes(in.readableBytes());
            ctx.close();
            return;
        }

        if (messageLength > MAX_MESSAGE_SIZE) {
            LOG.error("MRCP message length {} exceeds maximum allowed {}", messageLength, MAX_MESSAGE_SIZE);
            in.skipBytes(in.readableBytes());
            ctx.close();
            return;
        }

        if (in.readableBytes() < messageLength) {
            // Need more octets to assemble the full message
            return;
        }

        byte[] messageBytes = new byte[(int) messageLength];
        in.readBytes(messageBytes);

        try {
            MrcpMessage message = MrcpMessageParser.parse(messageBytes);
            out.add(message);
        } catch (Exception e) {
            LOG.error("Failed to parse MRCP message: {}", e.getMessage(), e);
        }
    }
}
