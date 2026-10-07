package net.pilgrim.sip.rtp.media;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoertzelDtmfDetectorTest {

    private static final char[] ALL_DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '#', 'A', 'B', 'C', 'D'
    };

    @Test
    void testDetectAllDtmfDigits() {
        for (char digit : ALL_DIGITS) {
            List<Character> detected = new ArrayList<>();
            GoertzelDtmfDetector detector = new GoertzelDtmfDetector(detected::add);

            // Generate 80 ms tone (4 frames of 20ms = 4 * 160 samples = 640 samples)
            byte[] pcm = DtmfToneGenerator.generateTone(digit, 80, 8000);

            // Feed as 4 consecutive 20ms frames
            for (int i = 0; i < 4; i++) {
                byte[] framePcm = new byte[320];
                System.arraycopy(pcm, i * 320, framePcm, 0, 320);
                detector.process(new AudioFrame("call-test", framePcm, 8000, 1, i * 160, i, AudioFrame.Direction.INBOUND));
            }

            assertEquals(1, detected.size(), "Should detect digit '" + digit + "' exactly once");
            assertEquals(digit, (char) detected.get(0));
        }
    }

    @Test
    void testDebouncingForLongTone() {
        List<Character> detected = new ArrayList<>();
        GoertzelDtmfDetector detector = new GoertzelDtmfDetector(detected::add);

        // 200 ms tone = 10 frames of 20ms
        byte[] pcm = DtmfToneGenerator.generateTone('5', 200, 8000);

        for (int i = 0; i < 10; i++) {
            byte[] framePcm = new byte[320];
            System.arraycopy(pcm, i * 320, framePcm, 0, 320);
            detector.process(new AudioFrame("call-test", framePcm, 8000, 1, i * 160, i, AudioFrame.Direction.INBOUND));
        }

        // Even though 10 frames arrived, listener should fire exactly once for this single continuous tone
        assertEquals(1, detected.size());
        assertEquals('5', (char) detected.get(0));
    }

    @Test
    void testDigitSequenceWithSilence() {
        List<Character> detected = new ArrayList<>();
        GoertzelDtmfDetector detector = new GoertzelDtmfDetector(detected::add);

        // Digit '1': 60ms (3 frames)
        byte[] tone1 = DtmfToneGenerator.generateTone('1', 60, 8000);
        for (int i = 0; i < 3; i++) {
            byte[] framePcm = new byte[320];
            System.arraycopy(tone1, i * 320, framePcm, 0, 320);
            detector.process(new AudioFrame("call-test", framePcm, 8000, 1, i * 160, i, AudioFrame.Direction.INBOUND));
        }

        // Silence: 60ms (3 frames)
        byte[] silence = new byte[320];
        for (int i = 3; i < 6; i++) {
            detector.process(new AudioFrame("call-test", silence, 8000, 1, i * 160, i, AudioFrame.Direction.INBOUND));
        }

        // Digit '2': 60ms (3 frames)
        byte[] tone2 = DtmfToneGenerator.generateTone('2', 60, 8000);
        for (int i = 6; i < 9; i++) {
            byte[] framePcm = new byte[320];
            System.arraycopy(tone2, (i - 6) * 320, framePcm, 0, 320);
            detector.process(new AudioFrame("call-test", framePcm, 8000, 1, i * 160, i, AudioFrame.Direction.INBOUND));
        }

        assertEquals(2, detected.size());
        assertEquals('1', (char) detected.get(0));
        assertEquals('2', (char) detected.get(1));
    }

    @Test
    void testSilenceProducesNoDetection() {
        List<Character> detected = new ArrayList<>();
        GoertzelDtmfDetector detector = new GoertzelDtmfDetector(detected::add);

        byte[] silence = new byte[320];
        for (int i = 0; i < 10; i++) {
            detector.process(new AudioFrame("call-test", silence, 8000, 1, i * 160, i, AudioFrame.Direction.INBOUND));
        }

        assertTrue(detected.isEmpty(), "Silence should not trigger any DTMF detection");
    }

    @Test
    void testSinglePureToneRejected() {
        List<Character> detected = new ArrayList<>();
        GoertzelDtmfDetector detector = new GoertzelDtmfDetector(detected::add);

        // A single 697 Hz tone without high group component must NOT trigger DTMF
        byte[] pcm = new byte[640];
        for (int i = 0; i < 320; i++) {
            short sample = (short) (14000.0 * Math.sin(2.0 * Math.PI * 697.0 * i / 8000.0));
            pcm[2 * i] = (byte) (sample & 0xFF);
            pcm[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        for (int i = 0; i < 2; i++) {
            byte[] frame = new byte[320];
            System.arraycopy(pcm, i * 320, frame, 0, 320);
            detector.process(new AudioFrame("call-test", frame, 8000, 1, i * 160, i, AudioFrame.Direction.INBOUND));
        }

        assertTrue(detected.isEmpty(), "Single tone without high group component must be rejected");
    }
}
