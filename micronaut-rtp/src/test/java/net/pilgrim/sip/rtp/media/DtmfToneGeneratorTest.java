package net.pilgrim.sip.rtp.media;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DtmfToneGeneratorTest {

    private static final Map<Character, int[]> EXPECTED = Map.ofEntries(
            Map.entry('1', new int[]{697, 1209, 1}),
            Map.entry('2', new int[]{697, 1336, 2}),
            Map.entry('3', new int[]{697, 1477, 3}),
            Map.entry('A', new int[]{697, 1633, 12}),
            Map.entry('4', new int[]{770, 1209, 4}),
            Map.entry('5', new int[]{770, 1336, 5}),
            Map.entry('6', new int[]{770, 1477, 6}),
            Map.entry('B', new int[]{770, 1633, 13}),
            Map.entry('7', new int[]{852, 1209, 7}),
            Map.entry('8', new int[]{852, 1336, 8}),
            Map.entry('9', new int[]{852, 1477, 9}),
            Map.entry('C', new int[]{852, 1633, 14}),
            Map.entry('*', new int[]{941, 1209, 10}),
            Map.entry('0', new int[]{941, 1336, 0}),
            Map.entry('#', new int[]{941, 1477, 11}),
            Map.entry('D', new int[]{941, 1633, 15})
    );

    @Test
    void testDtmfFrequenciesAndEventCodes() {
        for (Map.Entry<Character, int[]> entry : EXPECTED.entrySet()) {
            char digit = entry.getKey();
            int[] exp = entry.getValue();

            assertTrue(DtmfToneGenerator.isDtmfChar(digit));
            int[] freqs = DtmfToneGenerator.getFrequencies(digit);
            assertEquals(exp[0], freqs[0], "Low freq for " + digit);
            assertEquals(exp[1], freqs[1], "High freq for " + digit);

            int code = DtmfToneGenerator.charToEventCode(digit);
            assertEquals(exp[2], code, "Event code for " + digit);

            char roundTrip = DtmfToneGenerator.eventCodeToChar(code);
            assertEquals(Character.toUpperCase(digit), roundTrip);
        }
    }

    @Test
    void testInvalidDigitThrows() {
        assertFalse(DtmfToneGenerator.isDtmfChar('X'));
        assertThrows(IllegalArgumentException.class, () -> DtmfToneGenerator.getFrequencies('X'));
        assertThrows(IllegalArgumentException.class, () -> DtmfToneGenerator.charToEventCode('X'));
        assertThrows(IllegalArgumentException.class, () -> DtmfToneGenerator.eventCodeToChar(99));
    }

    @Test
    void testGenerateToneOutputLength() {
        // 100 ms at 8000 Hz: 800 samples = 1600 bytes
        byte[] pcm = DtmfToneGenerator.generateTone('5', 100);
        assertNotNull(pcm);
        assertEquals(1600, pcm.length);

        // Check non-zero signal
        boolean hasNonZero = false;
        for (byte b : pcm) {
            if (b != 0) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "Tone should contain audio samples");
    }

    @Test
    void testGenerateToneWithSilence() {
        // 50ms tone (400 samples = 800 bytes) + 50ms silence (800 bytes) = 1600 bytes
        byte[] pcm = DtmfToneGenerator.generateToneWithSilence('1', 50, 50);
        assertEquals(1600, pcm.length);

        // Last 800 bytes should be silence (all zeroes)
        for (int i = 800; i < 1600; i++) {
            assertEquals(0, pcm[i], "Trailing portion must be silence");
        }
    }

    @Test
    void testGenerateSequence() {
        // 3 digits: "123", each 50ms tone + 50ms pause = 100ms per digit = 300ms total
        // 300 ms at 8 kHz = 2400 samples = 4800 bytes
        byte[] sequence = DtmfToneGenerator.generateSequence("123", 50, 50, 8000);
        assertEquals(4800, sequence.length);
    }
}
