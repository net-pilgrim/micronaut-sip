package net.pilgrim.sip.rtp.media;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a decoded linear PCM audio frame within an RTP media stream.
 * Typically signed 16-bit little-endian linear PCM (PCM-16LE).
 */
public final class AudioFrame implements Serializable {

    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    private final String callId;
    private final byte[] pcm16Le;
    private final int sampleRate; // e.g. 8000 Hz
    private final int channels;   // 1 for mono
    private final long timestamp; // RTP timestamp
    private final int sequenceNumber;
    private final Direction direction;

    public AudioFrame(String callId,
                      byte[] pcm16Le,
                      int sampleRate,
                      int channels,
                      long timestamp,
                      int sequenceNumber,
                      Direction direction) {
        this.callId = Objects.requireNonNull(callId, "callId");
        this.pcm16Le = pcm16Le != null ? pcm16Le : new byte[0];
        this.sampleRate = sampleRate > 0 ? sampleRate : 8000;
        this.channels = channels > 0 ? channels : 1;
        this.timestamp = timestamp;
        this.sequenceNumber = sequenceNumber;
        this.direction = direction != null ? direction : Direction.INBOUND;
    }

    public String getCallId() {
        return callId;
    }

    public byte[] getPcm16Le() {
        return pcm16Le;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public Direction getDirection() {
        return direction;
    }

    public boolean isInbound() {
        return direction == Direction.INBOUND;
    }

    public boolean isOutbound() {
        return direction == Direction.OUTBOUND;
    }

    public int getSampleCount() {
        return pcm16Le.length / 2;
    }

    public int getDurationMs() {
        int samples = getSampleCount();
        return sampleRate > 0 ? (samples * 1000) / sampleRate : 0;
    }

    /**
     * Converts the PCM-16LE byte array into signed 16-bit short samples.
     * Ideal for Goertzel DTMF detection, VAD, FFT, and other audio DSP algorithms.
     */
    public short[] toShortArray() {
        int sampleCount = pcm16Le.length / 2;
        short[] samples = new short[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int low = pcm16Le[2 * i] & 0xFF;
            int high = pcm16Le[2 * i + 1];
            samples[i] = (short) ((high << 8) | low);
        }
        return samples;
    }

    /**
     * Calculates the Root-Mean-Square (RMS) energy of the frame.
     * Often used as an energy threshold for Voice Activity Detection (VAD).
     *
     * @return normalized RMS value in range [0.0, 1.0]
     */
    public double calculateRms() {
        short[] samples = toShortArray();
        if (samples.length == 0) {
            return 0.0;
        }
        long sumSquares = 0;
        for (short s : samples) {
            sumSquares += (long) s * s;
        }
        double meanSquare = (double) sumSquares / samples.length;
        return Math.sqrt(meanSquare) / 32768.0;
    }

    /**
     * Calculates the RMS energy in decibels relative to full scale (dBFS).
     * Silence is typically &lt;= -50 dBFS, speech is typically -30 to -15 dBFS.
     */
    public double calculateDbfs() {
        double rms = calculateRms();
        if (rms <= 1e-6) {
            return -120.0;
        }
        return 20.0 * Math.log10(rms);
    }

    @Override
    public String toString() {
        return "AudioFrame[callId=" + callId +
                ", direction=" + direction +
                ", samples=" + getSampleCount() +
                ", rate=" + sampleRate + "Hz" +
                ", seq=" + sequenceNumber +
                ", ts=" + timestamp + "]";
    }
}
