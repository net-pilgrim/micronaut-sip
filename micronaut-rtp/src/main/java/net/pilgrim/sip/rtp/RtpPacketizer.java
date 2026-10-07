package net.pilgrim.sip.rtp;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Creates sequential RTP packets for a single stream/SSRC.
 */
public final class RtpPacketizer {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final int payloadType;
    private final int clockRate;
    private final long ssrc;
    private final AtomicInteger sequenceNumber;
    private final AtomicLong timestamp;

    public RtpPacketizer(int payloadType, int clockRate) {
        this(payloadType, clockRate, RANDOM.nextInt(0x1_0000), RANDOM.nextInt());
    }

    public RtpPacketizer(int payloadType, int clockRate, int initialSequenceNumber, long initialTimestamp) {
        if (payloadType < 0 || payloadType > 127) {
            throw new IllegalArgumentException("payloadType must be between 0 and 127");
        }
        if (clockRate <= 0) {
            throw new IllegalArgumentException("clockRate must be positive");
        }
        if (initialSequenceNumber < 0 || initialSequenceNumber > 0xFFFF) {
            throw new IllegalArgumentException("initialSequenceNumber must be between 0 and 65535");
        }

        this.payloadType = payloadType;
        this.clockRate = clockRate;
        this.ssrc = RANDOM.nextInt() & 0xFFFF_FFFFL;
        this.sequenceNumber = new AtomicInteger(initialSequenceNumber);
        this.timestamp = new AtomicLong(initialTimestamp & 0xFFFF_FFFFL);
    }

    public int getPayloadType() {
        return payloadType;
    }

    public int getClockRate() {
        return clockRate;
    }

    public long getSsrc() {
        return ssrc;
    }

    public int getCurrentSequenceNumber() {
        return sequenceNumber.get();
    }

    public long getCurrentTimestamp() {
        return timestamp.get();
    }

    public int getAndAdvanceSequenceNumber(int delta) {
        return sequenceNumber.getAndUpdate(val -> (val + delta) & 0xFFFF);
    }

    public long getAndAdvanceTimestamp(long delta) {
        return timestamp.getAndUpdate(val -> (val + delta) & 0xFFFF_FFFFL);
    }

    public RtpPacket packetize(byte[] encodedPayload, int sampleCount, boolean marker) {
        Objects.requireNonNull(encodedPayload, "encodedPayload");
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("sampleCount must be positive");
        }
        int seq = sequenceNumber.getAndUpdate(value -> (value + 1) & 0xFFFF);
        long packetTimestamp = timestamp.getAndUpdate(value -> (value + sampleCount) & 0xFFFF_FFFFL);

        return new RtpPacket(marker, payloadType, seq, packetTimestamp, ssrc, encodedPayload);
    }
}

