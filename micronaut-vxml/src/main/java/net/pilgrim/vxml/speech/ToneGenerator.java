package net.pilgrim.vxml.speech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility for generating standard PCM-16LE 8 kHz mono tones for prompt audio and audible cues.
 */
public final class ToneGenerator {

    private ToneGenerator() {}

    /**
     * Generates a 16-bit mono PCM-16LE sine wave at 8000 Hz.
     *
     * @param frequencyHz frequency in Hertz
     * @param durationMs duration in milliseconds
     * @return 8000 Hz, 16-bit mono PCM-16LE signed byte array
     */
    public static byte[] generateTone(int frequencyHz, int durationMs) {
        int sampleRate = 8000;
        int numSamples = (sampleRate * durationMs) / 1000;
        ByteBuffer buffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < numSamples; i++) {
            double angle = 2.0 * Math.PI * i * frequencyHz / sampleRate;
            short sample = (short) (Math.sin(angle) * 16000);
            buffer.putShort(sample);
        }
        return buffer.array();
    }
}
