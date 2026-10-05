package net.pilgrim.netann.service;

import net.pilgrim.netann.model.AnnouncementParams;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streams PCM-16LE audio data over an active RtpMediaSession at 20ms intervals (50 packets/second),
 * enforcing RFC 4240 repeat, delay, and duration parameters along with local security duration caps.
 */
public class AnnouncementPlayer {

    private static final Logger LOG = LoggerFactory.getLogger(AnnouncementPlayer.class);
    private static final int BYTES_PER_20MS_FRAME = 320; // 160 samples * 2 bytes/sample @ 8 kHz

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "netann-rtp-player");
                t.setDaemon(true);
                return t;
            });

    private final RtpMediaSession mediaSession;
    private final byte[] audioData;
    private final AnnouncementParams params;
    private final long maxDurationCapMs;
    private final long effectiveDurationMs;
    private final int maxRepeatCount;
    private final Runnable onComplete;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    private ScheduledFuture<?> scheduledFuture;
    private int currentOffset = 0;
    private int currentRepeat = 0;
    private long delayUntil = 0;
    private long startTimestamp = 0;
    private boolean isFirstPacket = true;

    public AnnouncementPlayer(RtpMediaSession mediaSession,
                              byte[] audioData,
                              AnnouncementParams params,
                              Runnable onComplete) {
        this(mediaSession, audioData, params, 300_000L, 100, onComplete);
    }

    public AnnouncementPlayer(RtpMediaSession mediaSession,
                              byte[] audioData,
                              AnnouncementParams params,
                              long maxDurationCapMs,
                              int maxRepeatCount,
                              Runnable onComplete) {
        this.mediaSession = Objects.requireNonNull(mediaSession, "mediaSession");
        this.audioData = (audioData != null) ? audioData : new byte[0];
        this.params = (params != null) ? params : new AnnouncementParams(null, 1, 0, 0, null, null);
        this.maxDurationCapMs = maxDurationCapMs > 0 ? maxDurationCapMs : 300_000L;
        this.maxRepeatCount = maxRepeatCount > 0 ? maxRepeatCount : 100;
        this.onComplete = onComplete;

        if (this.params.getDurationMs() > 0) {
            this.effectiveDurationMs = Math.min(this.params.getDurationMs(), this.maxDurationCapMs);
        } else {
            this.effectiveDurationMs = this.maxDurationCapMs;
        }
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        this.startTimestamp = System.currentTimeMillis();
        this.currentOffset = 0;
        this.currentRepeat = 0;
        this.delayUntil = 0;
        this.isFirstPacket = true;

        if (audioData.length == 0) {
            LOG.info("Announcement audio data is empty, completing immediately for Call-ID: {}", mediaSession.getCallId());
            triggerComplete();
            return;
        }

        LOG.info("Starting announcement playback for Call-ID: {} (length: {} bytes, effectiveDuration: {}ms, params: {})",
                mediaSession.getCallId(), audioData.length, effectiveDurationMs, params);

        this.scheduledFuture = SCHEDULER.scheduleAtFixedRate(this::tick, 0, 20, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        if (!running.get() || mediaSession.isClosed()) {
            stop();
            return;
        }

        long now = System.currentTimeMillis();

        // 1. Check max duration cap
        if (effectiveDurationMs > 0 && (now - startTimestamp) >= effectiveDurationMs) {
            LOG.info("Effective duration limit reached ({}ms) for Call-ID: {}. Terminating playback.",
                    effectiveDurationMs, mediaSession.getCallId());
            triggerComplete();
            return;
        }

        // 2. Check delay between repetitions
        if (now < delayUntil) {
            return;
        }

        // 3. Slice and send 20ms audio frame
        if (currentOffset < audioData.length) {
            int remaining = audioData.length - currentOffset;
            int frameLen = Math.min(BYTES_PER_20MS_FRAME, remaining);

            byte[] frame = new byte[BYTES_PER_20MS_FRAME];
            System.arraycopy(audioData, currentOffset, frame, 0, frameLen);
            currentOffset += frameLen;

            try {
                mediaSession.sendAudioFrame(frame, isFirstPacket).subscribe(
                        null,
                        e -> LOG.warn("Failed sending RTP audio frame for Call-ID: {}: {}", mediaSession.getCallId(), e.getMessage())
                );
                isFirstPacket = false;
            } catch (Exception e) {
                LOG.warn("Failed sending RTP audio frame for Call-ID: {}: {}", mediaSession.getCallId(), e.getMessage());
            }
        }

        // 4. Check if current repeat finished
        if (currentOffset >= audioData.length) {
            currentRepeat++;
            boolean shouldRepeat = (params.isRepeatForever() && currentRepeat < maxRepeatCount)
                    || (!params.isRepeatForever() && currentRepeat < params.getRepeat());
            if (shouldRepeat) {
                currentOffset = 0;
                isFirstPacket = true;
                if (params.getDelayMs() > 0) {
                    delayUntil = System.currentTimeMillis() + params.getDelayMs();
                }
            } else {
                LOG.info("Announcement finished all {} repetitions (cap={}) for Call-ID: {}",
                        currentRepeat, maxRepeatCount, mediaSession.getCallId());
                triggerComplete();
            }
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    private void triggerComplete() {
        stop();
        if (completed.compareAndSet(false, true)) {
            if (onComplete != null) {
                try {
                    onComplete.run();
                } catch (Throwable t) {
                    LOG.error("Error executing onComplete callback for Call-ID: {}", mediaSession.getCallId(), t);
                }
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isCompleted() {
        return completed.get();
    }

    public long getEffectiveDurationMs() {
        return effectiveDurationMs;
    }
}
