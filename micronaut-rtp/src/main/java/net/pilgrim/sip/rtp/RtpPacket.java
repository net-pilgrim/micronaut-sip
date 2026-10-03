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

    public static RtpPacket parse(byte[] packetBytes) {
        if (packetBytes == null || packetBytes.length < FIXED_HEADER_BYTES) {
            throw new IllegalArgumentException("RTP packet must be at least 12 bytes");
        }

        int first = packetBytes[0] & 0xFF;
        int version = (first >>> 6) & 0x03;
        if (version != 2) {
            throw new IllegalArgumentException("Unsupported RTP version: " + version);
        }
        boolean padding = (first & 0x20) != 0;
        boolean extension = (first & 0x10) != 0;
        int csrcCount = first & 0x0F;

        int headerSize = FIXED_HEADER_BYTES + csrcCount * 4;
        if (packetBytes.length < headerSize) {
            throw new IllegalArgumentException("Invalid RTP packet: truncated CSRC list");
        }

        if (extension) {
            if (packetBytes.length < headerSize + 4) {
                throw new IllegalArgumentException("Invalid RTP packet: truncated extension header");
            }
            int extensionLengthWords =
                    ((packetBytes[headerSize + 2] & 0xFF) << 8) | (packetBytes[headerSize + 3] & 0xFF);
            headerSize += 4 + extensionLengthWords * 4;
            if (packetBytes.length < headerSize) {
                throw new IllegalArgumentException("Invalid RTP packet: truncated extension payload");
            }
        }

        int second = packetBytes[1] & 0xFF;
        boolean marker = (second & 0x80) != 0;
        int payloadType = second & 0x7F;
        int sequenceNumber = ((packetBytes[2] & 0xFF) << 8) | (packetBytes[3] & 0xFF);
        long timestamp =
                ((packetBytes[4] & 0xFFL) << 24)
                        | ((packetBytes[5] & 0xFFL) << 16)
                        | ((packetBytes[6] & 0xFFL) << 8)
                        | (packetBytes[7] & 0xFFL);
        long ssrc =
                ((packetBytes[8] & 0xFFL) << 24)
                        | ((packetBytes[9] & 0xFFL) << 16)
                        | ((packetBytes[10] & 0xFFL) << 8)
                        | (packetBytes[11] & 0xFFL);

        int payloadStart = headerSize;
        int payloadEnd = packetBytes.length;
        if (padding) {
            int paddingBytes = packetBytes[packetBytes.length - 1] & 0xFF;
            if (paddingBytes <= 0 || paddingBytes > packetBytes.length - payloadStart) {
                throw new IllegalArgumentException("Invalid RTP packet padding length");
            }
            payloadEnd -= paddingBytes;
        }

        byte[] payload = Arrays.copyOfRange(packetBytes, payloadStart, payloadEnd);
        return new RtpPacket(marker, payloadType, sequenceNumber, timestamp, ssrc, payload);
    }
}

