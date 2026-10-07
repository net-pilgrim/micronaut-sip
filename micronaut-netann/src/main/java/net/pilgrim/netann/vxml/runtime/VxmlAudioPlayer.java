package net.pilgrim.netann.vxml.runtime;

import net.pilgrim.sip.rtp.media.RtpMediaSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streams prompt audio over an active RtpMediaSession in 20ms frames (160 samples @ 8 kHz),
 * with immediate stop support for barge-in DTMF interruption.
 */
public class VxmlAudioPlayer {

    private static final Logger LOG = LoggerFactory.getLogger(VxmlAudioPlayer.class);
    private static final int BYTES_PER_20MS_FRAME = 320; // 160 samples * 2 bytes/sample @ 8 kHz

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "vxml-audio-player");
                t.setDaemon(true);
                return t;
            });

    private final RtpMediaSession mediaSession;
    private final byte[] audioData;
    private final Runnable onComplete;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    private ScheduledFuture<?> scheduledFuture;
    private int currentOffset = 0;
    private boolean isFirstPacket = true;

    public VxmlAudioPlayer(RtpMediaSession mediaSession, byte[] audioData, Runnable onComplete) {
        this.mediaSession = Objects.requireNonNull(mediaSession, "mediaSession cannot be null");
        this.audioData = (audioData != null) ? audioData : new byte[0];
        this.onComplete = onComplete;
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        this.currentOffset = 0;
        this.isFirstPacket = true;

        if (audioData.length == 0) {
            triggerComplete();
            return;
        }

        this.scheduledFuture = SCHEDULER.scheduleAtFixedRate(this::tick, 0, 20, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        if (!running.get() || mediaSession.isClosed()) {
            stop();
            return;
        }

        if (currentOffset < audioData.length) {
            int remaining = audioData.length - currentOffset;
            int frameLen = Math.min(BYTES_PER_20MS_FRAME, remaining);

            byte[] frame = new byte[BYTES_PER_20MS_FRAME];
            System.arraycopy(audioData, currentOffset, frame, 0, frameLen);
            currentOffset += frameLen;

            try {
                mediaSession.sendAudioFrame(frame, isFirstPacket).subscribe(
                        null,
                        e -> LOG.debug("Error sending VXML RTP frame: {}", e.getMessage())
                );
                isFirstPacket = false;
            } catch (Exception e) {
                LOG.debug("Exception sending VXML RTP frame: {}", e.getMessage());
            }
        }

        if (currentOffset >= audioData.length) {
            triggerComplete();
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
                    LOG.error("Error executing VxmlAudioPlayer onComplete: {}", t.getMessage(), t);
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
