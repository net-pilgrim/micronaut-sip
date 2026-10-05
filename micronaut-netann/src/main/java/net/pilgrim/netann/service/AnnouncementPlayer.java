package net.pilgrim.netann.service;

import net.pilgrim.netann.model.AnnouncementParams;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streams PCM-16LE audio data over an active RtpMediaSession at 20ms intervals (50 packets/second),
 * enforcing RFC 4240 repeat, delay, and duration parameters.
 */
public class AnnouncementPlayer {

    private static final Logger LOG = LoggerFactory.getLogger(AnnouncementPlayer.class);
    private static final int BYTES_PER_20MS_FRAME = 320; // 160 samples * 2 bytes/sample @ 8 kHz

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "netann-rtp-player");
        t.setDaemon(true);
        return t;
    });

    private final RtpMediaSession mediaSession;
    private final byte[] audioData;
    private final AnnouncementParams params;
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
        this.mediaSession = Objects.requireNonNull(mediaSession, "mediaSession");
        this.audioData = (audioData != null) ? audioData : new byte[0];
        this.params = (params != null) ? params : new AnnouncementParams(null, 1, 0, 0, null, null);
        this.onComplete = onComplete;
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

        LOG.info("Starting announcement playback for Call-ID: {} (length: {} bytes, params: {})",
                mediaSession.getCallId(), audioData.length, params);

        this.scheduledFuture = SCHEDULER.scheduleAtFixedRate(this::tick, 0, 20, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        if (!running.get() || mediaSession.isClosed()) {
            stop();
            return;
        }

        long now = System.currentTimeMillis();

        // 1. Check max duration cap
        if (params.getDurationMs() > 0 && (now - startTimestamp) >= params.getDurationMs()) {
            LOG.info("Max duration reached ({}ms) for Call-ID: {}. Terminating playback.",
                    params.getDurationMs(), mediaSession.getCallId());
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
                mediaSession.sendAudioFrame(frame, isFirstPacket).block(Duration.ofMillis(50));
                isFirstPacket = false;
            } catch (Exception e) {
                LOG.warn("Failed sending RTP audio frame for Call-ID: {}: {}", mediaSession.getCallId(), e.getMessage());
            }
        }

        // 4. Check if current repeat finished
        if (currentOffset >= audioData.length) {
            currentRepeat++;
            if (params.isRepeatForever() || currentRepeat < params.getRepeat()) {
                currentOffset = 0;
                isFirstPacket = true;
                if (params.getDelayMs() > 0) {
                    delayUntil = System.currentTimeMillis() + params.getDelayMs();
                }
            } else {
                LOG.info("Announcement finished all {} repetitions for Call-ID: {}",
                        currentRepeat, mediaSession.getCallId());
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
}
