package net.pilgrim.sip.rtp.media;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AudioProcessor that records linear PCM-16LE frames from an RTP media stream
 * and exports them as {@link AudioRecording}.
 * <p>
 * Supports filtering by stream direction (inbound, outbound, or both), maximum
 * duration caps, and thread-safe starting/stopping.
 */
public class AudioRecorder implements AudioProcessor, Closeable {

    public enum DirectionFilter {
        INBOUND_ONLY,
        OUTBOUND_ONLY,
        BOTH
    }

    private final String callId;
    private final DirectionFilter directionFilter;
    private final long maxDurationMs;
    private final int maxBytes;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);

    private volatile int sampleRate = 8000;
    private volatile int channels = 1;

    /**
     * Creates an AudioRecorder for both stream directions with unlimited duration.
     *
     * @param callId call identifier
     */
    public AudioRecorder(String callId) {
        this(callId, DirectionFilter.BOTH, 0);
    }

    /**
     * Creates an AudioRecorder with the specified direction filter and maximum duration.
     *
     * @param callId          call identifier
     * @param directionFilter direction to capture
     * @param maxDurationMs   maximum duration in milliseconds (0 for unlimited)
     */
    public AudioRecorder(String callId, DirectionFilter directionFilter, long maxDurationMs) {
        this.callId = Objects.requireNonNull(callId, "callId cannot be null");
        this.directionFilter = directionFilter != null ? directionFilter : DirectionFilter.BOTH;
        this.maxDurationMs = maxDurationMs > 0 ? maxDurationMs : 0;
        // Default max memory limit: ~1 hour of 8kHz 16-bit mono = ~57.6 MB
        this.maxBytes = 60 * 1024 * 1024;
    }

    /**
     * Starts recording audio frames.
     */
    public synchronized void start() {
        if (recording.compareAndSet(false, true)) {
            startTime.set(System.currentTimeMillis());
            endTime.set(0);
        }
    }

    /**
     * Stops recording and returns the captured {@link AudioRecording}.
     *
     * @return recorded audio data
     */
    public synchronized AudioRecording stop() {
        if (recording.compareAndSet(true, false)) {
            endTime.set(System.currentTimeMillis());
        }
        return getRecording();
    }

    public boolean isRecording() {
        return recording.get();
    }

    public String getCallId() {
        return callId;
    }

    public DirectionFilter getDirectionFilter() {
        return directionFilter;
    }

    public long getMaxDurationMs() {
        return maxDurationMs;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    @Override
    public void process(AudioFrame frame) {
        if (!recording.get() || frame == null) {
            return;
        }

        // Apply direction filtering
        if (directionFilter == DirectionFilter.INBOUND_ONLY && !frame.isInbound()) {
            return;
        }
        if (directionFilter == DirectionFilter.OUTBOUND_ONLY && !frame.isOutbound()) {
            return;
        }

        byte[] pcm = frame.getPcm16Le();
        if (pcm == null || pcm.length == 0) {
            return;
        }

        this.sampleRate = frame.getSampleRate();
        this.channels = frame.getChannels();

        synchronized (buffer) {
            if (buffer.size() + pcm.length > maxBytes) {
                recording.set(false);
                endTime.set(System.currentTimeMillis());
                return;
            }
            buffer.write(pcm, 0, pcm.length);

            if (maxDurationMs > 0) {
                long durationMs = calculateDurationMs(buffer.size(), sampleRate, channels);
                if (durationMs >= maxDurationMs) {
                    recording.set(false);
                    endTime.set(System.currentTimeMillis());
                }
            }
        }
    }

    /**
     * Retrieves the current snapshot of captured audio as an {@link AudioRecording}.
     */
    public AudioRecording getRecording() {
        byte[] data;
        synchronized (buffer) {
            data = buffer.toByteArray();
        }
        long durationMs = calculateDurationMs(data.length, sampleRate, channels);
        return new AudioRecording(callId, data, sampleRate, channels, durationMs);
    }

    /**
     * Resets the recording buffer and timestamps.
     */
    public void reset() {
        synchronized (buffer) {
            buffer.reset();
        }
        startTime.set(0);
        endTime.set(0);
        recording.set(false);
    }

    /**
     * Returns the current recorded duration in milliseconds.
     */
    public long getDurationMs() {
        synchronized (buffer) {
            return calculateDurationMs(buffer.size(), sampleRate, channels);
        }
    }

    /**
     * Returns the number of raw PCM bytes captured so far.
     */
    public int getRecordedByteCount() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    private static long calculateDurationMs(int byteCount, int rate, int chans) {
        if (rate <= 0 || chans <= 0) {
            return 0;
        }
        int bytesPerSec = rate * chans * 2;
        return ((long) byteCount * 1000L) / bytesPerSec;
    }

    @Override
    public void close() {
        stop();
    }
}
