package net.pilgrim.sip.rtp.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Inband dual-tone multi-frequency (DTMF) detector using the Goertzel algorithm
 * operating on 8000 Hz linear PCM-16LE audio streams.
 * <p>
 * Evaluates the 4 row frequencies (697, 770, 852, 941 Hz) and 4 column frequencies
 * (1209, 1336, 1477, 1633 Hz). Applies energy thresholding, peak prominence checks,
 * twist validation (-8 dB to +4 dB), and a debounce state machine to ensure
 * reliable digit detection without double-triggering or false positives on speech.
 */
public class GoertzelDtmfDetector implements AudioProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(GoertzelDtmfDetector.class);

    private static final int SAMPLE_RATE = 8000;
    private static final int BLOCK_SIZE = 160; // 20 ms at 8000 Hz
    private static final int REQUIRED_CONSECUTIVE_HITS = 2; // 40 ms tone duration
    private static final int SILENCE_HITS_TO_RESET = 2;     // 40 ms pause

    private static final double MIN_ENERGY = 1e7;          // Minimum frame power for speech/tone
    private static final double MIN_TONE_POWER = 5e6;       // Minimum individual frequency power
    private static final double PEAK_RATIO_THRESHOLD = 2.0; // Dominance over 2nd harmonic/frequency

    // Low frequencies (Rows)
    private static final int[] ROW_FREQS = {697, 770, 852, 941};
    // High frequencies (Columns)
    private static final int[] COL_FREQS = {1209, 1336, 1477, 1633};

    private static final char[][] DTMF_GRID = {
            {'1', '2', '3', 'A'},
            {'4', '5', '6', 'B'},
            {'7', '8', '9', 'C'},
            {'*', '0', '#', 'D'}
    };

    // Precomputed Goertzel coefficients: 2.0 * cos(2 * PI * f / 8000)
    private static final double[] ROW_COEFFS = new double[4];
    private static final double[] COL_COEFFS = new double[4];

    static {
        for (int i = 0; i < ROW_FREQS.length; i++) {
            ROW_COEFFS[i] = 2.0 * Math.cos(2.0 * Math.PI * ROW_FREQS[i] / SAMPLE_RATE);
        }
        for (int i = 0; i < COL_FREQS.length; i++) {
            COL_COEFFS[i] = 2.0 * Math.cos(2.0 * Math.PI * COL_FREQS[i] / SAMPLE_RATE);
        }
    }

    private final List<Consumer<Character>> listeners = new CopyOnWriteArrayList<>();

    // Internal sample buffer for incoming audio
    private short[] sampleBuffer = new short[BLOCK_SIZE * 4];
    private int bufferLength = 0;

    // State machine for debouncing
    private char lastCandidate = 0;
    private int consecutiveHits = 0;
    private char emittedDigit = 0;
    private int consecutiveSilence = 0;

    public GoertzelDtmfDetector() {
    }

    public GoertzelDtmfDetector(Consumer<Character> listener) {
        if (listener != null) {
            addListener(listener);
        }
    }

    public void addListener(Consumer<Character> listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<Character> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void clearListeners() {
        listeners.clear();
    }

    @Override
    public synchronized void process(AudioFrame frame) {
        if (frame == null) {
            return;
        }

        short[] samples = frame.toShortArray();
        if (samples.length == 0) {
            return;
        }

        // Ensure buffer capacity
        if (bufferLength + samples.length > sampleBuffer.length) {
            sampleBuffer = Arrays.copyOf(sampleBuffer, Math.max(sampleBuffer.length * 2, bufferLength + samples.length));
        }

        System.arraycopy(samples, 0, sampleBuffer, bufferLength, samples.length);
        bufferLength += samples.length;

        // Process full blocks
        while (bufferLength >= BLOCK_SIZE) {
            char detected = detectBlock(sampleBuffer, 0, BLOCK_SIZE);
            updateStateMachine(detected);

            // Shift buffer
            System.arraycopy(sampleBuffer, BLOCK_SIZE, sampleBuffer, 0, bufferLength - BLOCK_SIZE);
            bufferLength -= BLOCK_SIZE;
        }
    }

    /**
     * Evaluates a single block of 160 PCM samples for DTMF tones.
     */
    private char detectBlock(short[] samples, int offset, int length) {
        double totalEnergy = 0.0;
        for (int i = 0; i < length; i++) {
            double s = samples[offset + i];
            totalEnergy += s * s;
        }

        if (totalEnergy < MIN_ENERGY) {
            return 0; // Below minimum energy threshold (silence/ambient noise)
        }

        // Calculate Goertzel powers for rows
        double[] rowPowers = new double[4];
        int bestRow = -1;
        double maxRowPower = 0.0;
        double secondRowPower = 0.0;

        for (int i = 0; i < 4; i++) {
            rowPowers[i] = computeGoertzelPower(samples, offset, length, ROW_COEFFS[i]);
            if (rowPowers[i] > maxRowPower) {
                secondRowPower = maxRowPower;
                maxRowPower = rowPowers[i];
                bestRow = i;
            } else if (rowPowers[i] > secondRowPower) {
                secondRowPower = rowPowers[i];
            }
        }

        // Calculate Goertzel powers for columns
        double[] colPowers = new double[4];
        int bestCol = -1;
        double maxColPower = 0.0;
        double secondColPower = 0.0;

        for (int i = 0; i < 4; i++) {
            colPowers[i] = computeGoertzelPower(samples, offset, length, COL_COEFFS[i]);
            if (colPowers[i] > maxColPower) {
                secondColPower = maxColPower;
                maxColPower = colPowers[i];
                bestCol = i;
            } else if (colPowers[i] > secondColPower) {
                secondColPower = colPowers[i];
            }
        }

        // Check power thresholds
        if (maxRowPower < MIN_TONE_POWER || maxColPower < MIN_TONE_POWER) {
            return 0;
        }

        // Peak prominence: best peak must clearly dominate the 2nd best in the group
        if (maxRowPower < secondRowPower * PEAK_RATIO_THRESHOLD) {
            return 0;
        }
        if (maxColPower < secondColPower * PEAK_RATIO_THRESHOLD) {
            return 0;
        }

        // Twist validation:
        // Normal twist (col / row) <= +4 dB (~2.51 ratio)
        // Reverse twist (row / col) <= +8 dB (~6.31 ratio -> col / row >= 0.158)
        double twistRatio = maxColPower / maxRowPower;
        if (twistRatio < 0.15 || twistRatio > 4.0) {
            return 0;
        }

        if (bestRow >= 0 && bestRow < 4 && bestCol >= 0 && bestCol < 4) {
            return DTMF_GRID[bestRow][bestCol];
        }

        return 0;
    }

    private static double computeGoertzelPower(short[] samples, int offset, int length, double coeff) {
        double s0;
        double s1 = 0.0;
        double s2 = 0.0;

        for (int i = 0; i < length; i++) {
            s0 = samples[offset + i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }

    /**
     * Debounce state machine to filter transient audio hits and trigger listeners once per tone.
     */
    private void updateStateMachine(char detected) {
        if (detected != 0) {
            consecutiveSilence = 0;
            if (detected == lastCandidate) {
                consecutiveHits++;
            } else {
                lastCandidate = detected;
                consecutiveHits = 1;
            }

            if (consecutiveHits >= REQUIRED_CONSECUTIVE_HITS && emittedDigit != detected) {
                emittedDigit = detected;
                LOG.debug("Inband DTMF tone detected: '{}'", detected);
                dispatchDigit(detected);
            }
        } else {
            consecutiveSilence++;
            if (consecutiveSilence >= SILENCE_HITS_TO_RESET) {
                lastCandidate = 0;
                consecutiveHits = 0;
                emittedDigit = 0;
            }
        }
    }

    private void dispatchDigit(char digit) {
        for (Consumer<Character> listener : listeners) {
            try {
                listener.accept(digit);
            } catch (Throwable t) {
                LOG.error("Error notifying DTMF listener for digit '{}'", digit, t);
            }
        }
    }

    /**
     * Resets the internal audio buffer and detector state machine.
     */
    public synchronized void reset() {
        bufferLength = 0;
        lastCandidate = 0;
        consecutiveHits = 0;
        emittedDigit = 0;
        consecutiveSilence = 0;
    }
}
