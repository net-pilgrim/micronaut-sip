package net.pilgrim.sip.rtp;

import net.pilgrim.sip.rtp.codec.G711AlawCodec;
import net.pilgrim.sip.rtp.codec.G711UlawCodec;
import net.pilgrim.sip.rtp.codec.RtpCodec;
import net.pilgrim.sip.rtp.codec.RtpCodecRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class G711CodecTest {

    @Test
    void ulawRoundTripsPcmWithinCompandingTolerance() {
        G711UlawCodec codec = new G711UlawCodec();
        byte[] pcm = toPcm16Le(new short[]{-30000, -12000, -1000, -10, 0, 10, 1000, 12000, 30000});

        byte[] encoded = codec.encodePcm16Le(pcm);
        byte[] decoded = codec.decodeToPcm16Le(encoded);
        short[] decodedSamples = fromPcm16Le(decoded);
        short[] originalSamples = fromPcm16Le(pcm);

        assertEquals(0, codec.payloadType());
        assertEquals(8_000, codec.clockRate());
        for (int i = 0; i < originalSamples.length; i++) {
            int diff = Math.abs(originalSamples[i] - decodedSamples[i]);
            assertTrue(diff < 1_500, "Unexpected ulaw delta at " + i + ": " + diff);
        }
    }

    @Test
    void alawRoundTripsPcmWithinCompandingTolerance() {
        G711AlawCodec codec = new G711AlawCodec();
        byte[] pcm = toPcm16Le(new short[]{-30000, -12000, -1000, -10, 0, 10, 1000, 12000, 30000});

        byte[] encoded = codec.encodePcm16Le(pcm);
        byte[] decoded = codec.decodeToPcm16Le(encoded);
        short[] decodedSamples = fromPcm16Le(decoded);
        short[] originalSamples = fromPcm16Le(pcm);

        assertEquals(8, codec.payloadType());
        assertEquals(8_000, codec.clockRate());
        for (int i = 0; i < originalSamples.length; i++) {
            int diff = Math.abs(originalSamples[i] - decodedSamples[i]);
            assertTrue(diff < 1_500, "Unexpected alaw delta at " + i + ": " + diff);
        }
    }

    @Test
    void registryProvidesG711Defaults() {
        RtpCodecRegistry registry = RtpCodecRegistry.withG711Defaults();
        RtpCodec ulaw = registry.findByPayloadType(0).orElseThrow();
        RtpCodec alaw = registry.findByPayloadType(8).orElseThrow();

        assertEquals("PCMU", ulaw.name());
        assertEquals("PCMA", alaw.name());
    }

    @Test
    void ulawEncodesZeroSampleToSilenceCodeword() {
        G711UlawCodec codec = new G711UlawCodec();
        byte[] encoded = codec.encodePcm16Le(toPcm16Le(new short[]{0}));
        assertArrayEquals(new byte[]{(byte) 0xFF}, encoded);
    }

    private static byte[] toPcm16Le(short[] samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0, p = 0; i < samples.length; i++, p += 2) {
            short sample = samples[i];
            bytes[p] = (byte) (sample & 0xFF);
            bytes[p + 1] = (byte) ((sample >>> 8) & 0xFF);
        }
        return bytes;
    }

    private static short[] fromPcm16Le(byte[] bytes) {
        short[] samples = new short[bytes.length / 2];
        for (int i = 0, p = 0; i < samples.length; i++, p += 2) {
            samples[i] = (short) ((bytes[p] & 0xFF) | (bytes[p + 1] << 8));
        }
        return samples;
    }
}

