package net.pilgrim.vxml.media;

import net.pilgrim.sip.rtp.media.RtpMediaSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * VxmlMedia implementation that uses RTP for audio transport.
 * Bridges VXML sessions with RTP media sessions (e.g., from Janus Gateway via SIP).
 */
public class RtpVxmlMedia implements VxmlMedia {
    private static final Logger log = LoggerFactory.getLogger(RtpVxmlMedia.class);

    private final RtpMediaSession rtpSession;
    private Consumer<Character> dtmfListener;
    private Consumer<byte[]> audioInputListener;
    private boolean bargeInAllowed = false;
    private volatile boolean audioPlaying = false;
    private Runnable currentPlaybackCallback;

    public RtpVxmlMedia(RtpMediaSession rtpSession) {
        this.rtpSession = rtpSession;

        // Listen for DTMF digits from RTP (RFC 4733 events)
        rtpSession.addDtmfListener(digit -> {
            log.debug("Received DTMF digit via RTP: {}", digit);
            if (dtmfListener != null) {
                dtmfListener.accept(digit);
            }

            // Handle barge-in if enabled
            if (bargeInAllowed && audioPlaying) {
                log.debug("Barge-in triggered by DTMF: {}", digit);
                stopAudio();
            }
        });

        // Listen for incoming audio frames (for speech recognition, if needed)
        rtpSession.incomingAudioFrames().subscribe(audioFrame -> {
            if (audioInputListener != null) {
                audioInputListener.accept(audioFrame.getPcm16Le());
            }
        });
    }

    @Override
    public void playAudio(byte[] pcmAudio, boolean bargeIn, Runnable onFinished) {
        log.debug("Playing audio via RTP: {} bytes, bargeIn={}", pcmAudio.length, bargeIn);

        this.bargeInAllowed = bargeIn;
        this.audioPlaying = true;
        this.currentPlaybackCallback = onFinished;

        // Send audio over RTP using the playAudio method
        rtpSession.playAudio(pcmAudio, () -> {
            log.debug("Audio playback completed");
            audioPlaying = false;
            bargeInAllowed = false;

            if (onFinished != null) {
                onFinished.run();
            }
            currentPlaybackCallback = null;
        });
    }

    @Override
    public void stopAudio() {
        if (audioPlaying) {
            log.debug("Stopping audio playback");
            rtpSession.stopAudio();
            audioPlaying = false;
            bargeInAllowed = false;

            if (currentPlaybackCallback != null) {
                currentPlaybackCallback.run();
                currentPlaybackCallback = null;
            }
        }
    }

    @Override
    public boolean isAudioPlaying() {
        return audioPlaying;
    }

    @Override
    public boolean isBargeInAllowed() {
        return bargeInAllowed;
    }

    @Override
    public void setDtmfListener(Consumer<Character> listener) {
        this.dtmfListener = listener;
    }

    @Override
    public void setAudioInputListener(Consumer<byte[]> listener) {
        this.audioInputListener = listener;
    }

    @Override
    public void sendDtmf(char digit) {
        log.debug("Sending DTMF digit via RTP: {}", digit);
        rtpSession.sendDtmf(digit);
    }

    /**
     * Gets the underlying RTP media session.
     * @return RTP media session
     */
    public RtpMediaSession getRtpSession() {
        return rtpSession;
    }

    /**
     * Closes the RTP session and releases resources.
     */
    public void close() {
        log.debug("Closing RTP VXML media");
        stopAudio();
        rtpSession.close();
    }
}
