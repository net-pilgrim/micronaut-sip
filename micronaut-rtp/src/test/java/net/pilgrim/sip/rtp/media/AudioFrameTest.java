package net.pilgrim.sip.rtp.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioFrameTest {

    @Test
    void testAudioFrameProperties() {
        byte[] pcm = new byte[320]; // 160 samples = 20ms at 8000Hz
        AudioFrame frame = new AudioFrame("call-1", pcm, 8000, 1, 1000L, 42, AudioFrame.Direction.INBOUND);

        assertEquals("call-1", frame.getCallId());
        assertEquals(8000, frame.getSampleRate());
        assertEquals(1, frame.getChannels());
        assertEquals(1000L, frame.getTimestamp());
        assertEquals(42, frame.getSequenceNumber());
        assertEquals(AudioFrame.Direction.INBOUND, frame.getDirection());
        assertTrue(frame.isInbound());
        assertFalse(frame.isOutbound());
        assertEquals(160, frame.getSampleCount());
        assertEquals(20, frame.getDurationMs());
    }

    @Test
    void testToShortArrayLittleEndianConversion() {
        // Sample 0: 0x0102 (low=0x02, high=0x01) -> 258
        // Sample 1: -1 (low=0xFF, high=0xFF) -> -1
        byte[] pcm = new byte[] {
                (byte) 0x02, (byte) 0x01,
                (byte) 0xFF, (byte) 0xFF
        };
        AudioFrame frame = new AudioFrame("call-1", pcm, 8000, 1, 0L, 0, AudioFrame.Direction.INBOUND);

        short[] samples = frame.toShortArray();
        assertEquals(2, samples.length);
        assertEquals(258, samples[0]);
        assertEquals(-1, samples[1]);
    }

    @Test
    void testRmsAndDbfsOnSilenceAndTone() {
        byte[] silence = new byte[320];
        AudioFrame silenceFrame = new AudioFrame("call-1", silence, 8000, 1, 0L, 0, AudioFrame.Direction.INBOUND);

        assertEquals(0.0, silenceFrame.calculateRms(), 1e-6);
        assertEquals(-120.0, silenceFrame.calculateDbfs(), 1e-6);

        // Generate synthetic sine wave (e.g. 1000 Hz at 8000 Hz sample rate, amplitude 16384)
        short[] toneSamples = new short[160];
        for (int i = 0; i < toneSamples.length; i++) {
            toneSamples[i] = (short) (16384 * Math.sin(2 * Math.PI * 1000 * i / 8000.0));
        }
        byte[] toneBytes = new byte[320];
        for (int i = 0; i < toneSamples.length; i++) {
            toneBytes[2 * i] = (byte) (toneSamples[i] & 0xFF);
            toneBytes[2 * i + 1] = (byte) ((toneSamples[i] >> 8) & 0xFF);
        }
        AudioFrame toneFrame = new AudioFrame("call-1", toneBytes, 8000, 1, 0L, 0, AudioFrame.Direction.INBOUND);

        double rms = toneFrame.calculateRms();
        assertTrue(rms > 0.3 && rms < 0.4, "RMS of half-scale sine should be ~0.35, was: " + rms);

        double dbfs = toneFrame.calculateDbfs();
        assertTrue(dbfs > -10.0 && dbfs < -8.0, "dBFS should be ~ -9.0 dBFS, was: " + dbfs);
    }
}
