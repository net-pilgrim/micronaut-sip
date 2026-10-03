package net.pilgrim.sip.rtp.codec;

import java.util.Objects;

/**
 * ITU-T G.711 A-law codec (RTP payload type 8, 8 kHz).
 */
public final class G711AlawCodec implements RtpCodec {

    private static final int[] SEGMENT_END = {0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF};

    @Override
    public String name() {
        return "PCMA";
    }

    @Override
    public int payloadType() {
        return 8;
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
            encoded[out] = linearToAlaw(sample);
        }
        return encoded;
    }

    @Override
    public byte[] decodeToPcm16Le(byte[] payload) {
        Objects.requireNonNull(payload, "payload");

        byte[] pcm = new byte[payload.length * 2];
        for (int i = 0, out = 0; i < payload.length; i++, out += 2) {
            short decoded = alawToLinear(payload[i]);
            pcm[out] = (byte) (decoded & 0xFF);
            pcm[out + 1] = (byte) ((decoded >>> 8) & 0xFF);
        }
        return pcm;
    }

    private static byte linearToAlaw(short sample) {
        int pcm = sample >> 3;
        int mask;
        if (pcm >= 0) {
            mask = 0xD5;
        } else {
            mask = 0x55;
            pcm = -pcm - 1;
            if (pcm < 0) {
                pcm = 0;
            }
        }

        int segment = findSegment(pcm);
        if (segment >= 8) {
            return (byte) (0x7F ^ mask);
        }

        int aval = segment << 4;
        if (segment < 2) {
            aval |= (pcm >> 1) & 0x0F;
        } else {
            aval |= (pcm >> segment) & 0x0F;
        }
        return (byte) (aval ^ mask);
    }

    private static short alawToLinear(byte alawValue) {
        int alaw = alawValue ^ 0x55;
        int t = (alaw & 0x0F) << 4;
        int segment = (alaw & 0x70) >> 4;
        switch (segment) {
            case 0 -> t += 8;
            case 1 -> t += 0x108;
            default -> {
                t += 0x108;
                t <<= (segment - 1);
            }
        }
        return (short) (((alaw & 0x80) != 0) ? t : -t);
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
