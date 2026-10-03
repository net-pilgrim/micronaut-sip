package net.pilgrim.sip.rtp;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Minimal RTP packet model and serializer/parser (RFC 3550).
 */
public final class RtpPacket {

    private static final int FIXED_HEADER_BYTES = 12;

    private final boolean marker;
    private final int payloadType;
    private final int sequenceNumber;
    private final long timestamp;
    private final long ssrc;
    private final byte[] payload;

    public RtpPacket(boolean marker,
                     int payloadType,
                     int sequenceNumber,
                     long timestamp,
                     long ssrc,
                     byte[] payload) {
        if (payloadType < 0 || payloadType > 127) {
            throw new IllegalArgumentException("payloadType must be between 0 and 127");
        }
        if (sequenceNumber < 0 || sequenceNumber > 0xFFFF) {
            throw new IllegalArgumentException("sequenceNumber must be between 0 and 65535");
        }
        this.marker = marker;
        this.payloadType = payloadType;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp & 0xFFFF_FFFFL;
        this.ssrc = ssrc & 0xFFFF_FFFFL;
        this.payload = Objects.requireNonNull(payload, "payload").clone();
    }

    public boolean isMarker() {
        return marker;
    }

    public int getPayloadType() {
        return payloadType;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getSsrc() {
        return ssrc;
    }

    public byte[] getPayload() {
        return payload.clone();
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(FIXED_HEADER_BYTES + payload.length);
        buffer.put((byte) 0x80); // version 2, no padding/extension/csrc
        int second = payloadType & 0x7F;
        if (marker) {
            second |= 0x80;
        }
        buffer.put((byte) second);
        buffer.putShort((short) (sequenceNumber & 0xFFFF));
        buffer.putInt((int) (timestamp & 0xFFFF_FFFFL));
        buffer.putInt((int) (ssrc & 0xFFFF_FFFFL));
        buffer.put(payload);
        return buffer.array();
    }

    public void encode(io.netty.buffer.ByteBuf out) {
        Objects.requireNonNull(out, "out");
        out.writeByte(0x80); // version 2, no padding/extension/csrc
        int second = payloadType & 0x7F;
        if (marker) {
            second |= 0x80;
        }
        out.writeByte(second);
        out.writeShort(sequenceNumber & 0xFFFF);
        out.writeInt((int) (timestamp & 0xFFFF_FFFFL));
        out.writeInt((int) (ssrc & 0xFFFF_FFFFL));
        out.writeBytes(payload);
    }

    public static RtpPacket parse(io.netty.buffer.ByteBuf buffer) {
        if (buffer == null || buffer.readableBytes() < FIXED_HEADER_BYTES) {
            throw new IllegalArgumentException("RTP packet must be at least 12 bytes");
        }

        short first = buffer.readUnsignedByte();
        int version = (first >>> 6) & 0x03;
        if (version != 2) {
            throw new IllegalArgumentException("Unsupported RTP version: " + version);
        }
        boolean padding = (first & 0x20) != 0;
        boolean extension = (first & 0x10) != 0;
        int csrcCount = first & 0x0F;

        int headerSize = FIXED_HEADER_BYTES + csrcCount * 4;
        if (buffer.readableBytes() + 1 < headerSize) {
            throw new IllegalArgumentException("Invalid RTP packet: truncated CSRC list");
        }

        short second = buffer.readUnsignedByte();
        boolean marker = (second & 0x80) != 0;
        int payloadType = second & 0x7F;
        int sequenceNumber = buffer.readUnsignedShort();
        long timestamp = buffer.readUnsignedInt();
        long ssrc = buffer.readUnsignedInt();

        if (csrcCount > 0) {
            buffer.skipBytes(csrcCount * 4);
        }

        if (extension) {
            if (buffer.readableBytes() < 4) {
                throw new IllegalArgumentException("Invalid RTP packet: truncated extension header");
            }
            buffer.skipBytes(2);
            int extensionLengthWords = buffer.readUnsignedShort();
            int extensionBytes = extensionLengthWords * 4;
            if (buffer.readableBytes() < extensionBytes) {
                throw new IllegalArgumentException("Invalid RTP packet: truncated extension payload");
            }
            buffer.skipBytes(extensionBytes);
        }

        int payloadLength = buffer.readableBytes();
        int paddingBytes = 0;
        if (padding) {
            if (payloadLength <= 0) {
                throw new IllegalArgumentException("Invalid RTP packet padding length");
            }
            paddingBytes = buffer.getUnsignedByte(buffer.writerIndex() - 1);
            if (paddingBytes <= 0 || paddingBytes > payloadLength) {
                throw new IllegalArgumentException("Invalid RTP packet padding length");
            }
            payloadLength -= paddingBytes;
        }

        byte[] payload = new byte[payloadLength];
        buffer.readBytes(payload);
        if (paddingBytes > 0) {
            buffer.skipBytes(paddingBytes);
        }
        return new RtpPacket(marker, payloadType, sequenceNumber, timestamp, ssrc, payload);
    }

    public static RtpPacket parse(byte[] packetBytes) {
        if (packetBytes == null) {
            throw new IllegalArgumentException("packetBytes cannot be null");
        }
        return parse(io.netty.buffer.Unpooled.wrappedBuffer(packetBytes));
    }
}

