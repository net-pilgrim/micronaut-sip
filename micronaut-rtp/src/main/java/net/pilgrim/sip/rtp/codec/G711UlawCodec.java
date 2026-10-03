package net.pilgrim.sip.rtp.codec;

import java.util.Objects;

/**
 * ITU-T G.711 μ-law codec (RTP payload type 0, 8 kHz).
 */
public final class G711UlawCodec implements RtpCodec {

    private static final int BIAS = 0x84;
    private static final int[] SEGMENT_END = {0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF};

    @Override
    public String name() {
        return "PCMU";
    }

    @Override
    public int payloadType() {
        return 0;
    }

    @Override
    public int clockRate() {
        return 8_000;
    }

    @Override
    public byte[] encodePcm16Le(byte[] pcm16Le) {
        Objects.requireNonNull(pcm16Le, "pcm16Le");
        if ((pcm16Le.length & 1) != 0) {
            throw new IllegalArgumentException("PCM 16-bit LE input must have an even length");
        }

        byte[] encoded = new byte[pcm16Le.length / 2];
        for (int i = 0, out = 0; i < pcm16Le.length; i += 2, out++) {
            short sample = (short) ((pcm16Le[i] & 0xFF) | (pcm16Le[i + 1] << 8));
            encoded[out] = linearToUlaw(sample);
        }
        return encoded;
    }

    @Override
    public byte[] decodeToPcm16Le(byte[] payload) {
        Objects.requireNonNull(payload, "payload");

        byte[] pcm = new byte[payload.length * 2];
        for (int i = 0, out = 0; i < payload.length; i++, out += 2) {
            short decoded = ulawToLinear(payload[i]);
            pcm[out] = (byte) (decoded & 0xFF);
            pcm[out + 1] = (byte) ((decoded >>> 8) & 0xFF);
        }
        return pcm;
    }

    private static byte linearToUlaw(short sample) {
        int pcm = sample;
        int mask;
        if (pcm < 0) {
            pcm = BIAS - pcm;
            mask = 0x7F;
        } else {
            pcm = BIAS + pcm;
            mask = 0xFF;
        }

        int segment = findSegment(pcm);
        if (segment >= 8) {
            return (byte) (0x7F ^ mask);
        }

        int mantissa = (pcm >> (segment + 3)) & 0x0F;
        int ulaw = (segment << 4) | mantissa;
        return (byte) (ulaw ^ mask);
    }

    private static short ulawToLinear(byte ulawValue) {
        int ulaw = ~ulawValue & 0xFF;
        int t = ((ulaw & 0x0F) << 3) + BIAS;
        t <<= (ulaw & 0x70) >> 4;
        return (short) (((ulaw & 0x80) != 0) ? (BIAS - t) : (t - BIAS));
    }

    private static int findSegment(int pcm) {
        for (int i = 0; i < SEGMENT_END.length; i++) {
            if (pcm <= SEGMENT_END[i]) {
                return i;
            }
        }
        return SEGMENT_END.length;
    }
}

