package net.pilgrim.sip.rtp.media;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Generates dual-tone multi-frequency (DTMF) linear PCM-16LE audio waveforms
 * conforming to ITU-T Recommendation Q.23 and RFC 4733 event code definitions.
 */
public final class DtmfToneGenerator {

    // Low frequency group (Hz)
    public static final int ROW_697 = 697;
    public static final int ROW_770 = 770;
    public static final int ROW_852 = 852;
    public static final int ROW_941 = 941;

    // High frequency group (Hz)
    public static final int COL_1209 = 1209;
    public static final int COL_1336 = 1336;
    public static final int COL_1477 = 1477;
    public static final int COL_1633 = 1633;

    private static final Map<Character, int[]> FREQUENCIES = Map.ofEntries(
            Map.entry('1', new int[]{ROW_697, COL_1209}),
            Map.entry('2', new int[]{ROW_697, COL_1336}),
            Map.entry('3', new int[]{ROW_697, COL_1477}),
            Map.entry('A', new int[]{ROW_697, COL_1633}),
            Map.entry('4', new int[]{ROW_770, COL_1209}),
            Map.entry('5', new int[]{ROW_770, COL_1336}),
            Map.entry('6', new int[]{ROW_770, COL_1477}),
            Map.entry('B', new int[]{ROW_770, COL_1633}),
            Map.entry('7', new int[]{ROW_852, COL_1209}),
            Map.entry('8', new int[]{ROW_852, COL_1336}),
            Map.entry('9', new int[]{ROW_852, COL_1477}),
            Map.entry('C', new int[]{ROW_852, COL_1633}),
            Map.entry('*', new int[]{ROW_941, COL_1209}),
            Map.entry('0', new int[]{ROW_941, COL_1336}),
            Map.entry('#', new int[]{ROW_941, COL_1477}),
            Map.entry('D', new int[]{ROW_941, COL_1633})
    );

    private DtmfToneGenerator() {
    }

    /**
     * Checks if a character represents a valid DTMF digit ('0'-'9', '*', '#', 'A'-'D').
     */
    public static boolean isDtmfChar(char c) {
        return FREQUENCIES.containsKey(Character.toUpperCase(c));
    }

    /**
     * Returns the pair of frequencies [low, high] for a given DTMF digit.
     */
    public static int[] getFrequencies(char digit) {
        int[] freqs = FREQUENCIES.get(Character.toUpperCase(digit));
        if (freqs == null) {
            throw new IllegalArgumentException("Unsupported DTMF digit: '" + digit + "'");
        }
        return freqs.clone();
    }

    /**
     * Converts a DTMF character to its RFC 4733 event code (0-15).
     */
    public static int charToEventCode(char digit) {
        char upper = Character.toUpperCase(digit);
        if (upper >= '0' && upper <= '9') {
            return upper - '0';
        }
        return switch (upper) {
            case '*' -> 10;
            case '#' -> 11;
            case 'A' -> 12;
            case 'B' -> 13;
            case 'C' -> 14;
            case 'D' -> 15;
            default -> throw new IllegalArgumentException("Unknown DTMF digit for event code: '" + digit + "'");
        };
    }

    /**
     * Converts an RFC 4733 event code (0-15) to its character representation.
     */
    public static char eventCodeToChar(int eventCode) {
        if (eventCode >= 0 && eventCode <= 9) {
            return (char) ('0' + eventCode);
        }
        return switch (eventCode) {
            case 10 -> '*';
            case 11 -> '#';
            case 12 -> 'A';
            case 13 -> 'B';
            case 14 -> 'C';
            case 15 -> 'D';
            default -> throw new IllegalArgumentException("Unknown RFC 4733 event code: " + eventCode);
        };
    }

    /**
     * Generates PCM-16LE audio bytes for a DTMF digit at 8000 Hz.
     *
     * @param digit      DTMF character ('0'-'9', '*', '#', 'A'-'D')
     * @param durationMs duration of the tone in milliseconds
     * @return linear PCM-16LE audio bytes
     */
    public static byte[] generateTone(char digit, int durationMs) {
        return generateTone(digit, durationMs, 8000);
    }

    /**
     * Generates PCM-16LE audio bytes for a DTMF digit at the specified sample rate.
     *
     * @param digit      DTMF character ('0'-'9', '*', '#', 'A'-'D')
     * @param durationMs duration of the tone in milliseconds
     * @param sampleRate sample rate in Hz (e.g. 8000)
     * @return linear PCM-16LE audio bytes
     */
    public static byte[] generateTone(char digit, int durationMs, int sampleRate) {
        int[] freqs = getFrequencies(digit);
        int lowFreq = freqs[0];
        int highFreq = freqs[1];

        int sampleCount = (sampleRate * Math.max(1, durationMs)) / 1000;
        byte[] pcm = new byte[sampleCount * 2];

        // Amplitude per tone: 14000 (~ -7 dBFS) to prevent clipping when summed
        final double amplitude = 14000.0;
        final double lowOmega = 2.0 * Math.PI * lowFreq / sampleRate;
        final double highOmega = 2.0 * Math.PI * highFreq / sampleRate;

        for (int i = 0; i < sampleCount; i++) {
            double sLow = Math.sin(lowOmega * i);
            double sHigh = Math.sin(highOmega * i);
            double sum = amplitude * (sLow + sHigh);
            short sample = (short) Math.max(-32768, Math.min(32767, Math.round(sum)));

            pcm[2 * i] = (byte) (sample & 0xFF);
            pcm[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        return pcm;
    }

    /**
     * Generates a DTMF tone followed by silence at 8000 Hz.
     *
     * @param digit          DTMF digit
     * @param toneDurationMs tone length in milliseconds
     * @param silenceMs      trailing silence in milliseconds
     * @return linear PCM-16LE audio bytes
     */
    public static byte[] generateToneWithSilence(char digit, int toneDurationMs, int silenceMs) {
        return generateToneWithSilence(digit, toneDurationMs, silenceMs, 8000);
    }

    /**
     * Generates a DTMF tone followed by silence at the specified sample rate.
     */
    public static byte[] generateToneWithSilence(char digit, int toneDurationMs, int silenceMs, int sampleRate) {
        byte[] tone = generateTone(digit, toneDurationMs, sampleRate);
        int silenceSamples = (sampleRate * Math.max(0, silenceMs)) / 1000;
        byte[] output = new byte[tone.length + silenceSamples * 2];
        System.arraycopy(tone, 0, output, 0, tone.length);
        // trailing bytes remain 0 (silence)
        return output;
    }

    /**
     * Generates audio for a sequence of DTMF digits separated by silence.
     *
     * @param digits         sequence of DTMF characters (e.g. "1234#")
     * @param toneDurationMs duration of each tone in milliseconds (e.g. 100)
     * @param silenceMs      inter-digit pause in milliseconds (e.g. 50)
     * @param sampleRate     sample rate in Hz (e.g. 8000)
     * @return composite linear PCM-16LE audio bytes
     */
    public static byte[] generateSequence(String digits, int toneDurationMs, int silenceMs, int sampleRate) {
        if (digits == null || digits.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (char c : digits.toCharArray()) {
            if (isDtmfChar(c)) {
                byte[] tone = generateToneWithSilence(c, toneDurationMs, silenceMs, sampleRate);
                baos.write(tone, 0, tone.length);
            }
        }
        return baos.toByteArray();
    }
}
