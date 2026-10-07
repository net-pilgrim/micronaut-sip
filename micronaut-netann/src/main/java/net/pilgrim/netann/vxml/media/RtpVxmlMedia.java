package net.pilgrim.netann.vxml.media;

import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.vxml.media.VxmlMedia;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Micronaut RTP implementation of {@link VxmlMedia}, binding VoiceXML interpreter
 * prompt playback and barge-in control to an active {@link RtpMediaSession}.
 */
public class RtpVxmlMedia implements VxmlMedia {

    private final RtpMediaSession mediaSession;
    private final AtomicBoolean bargeInAllowed = new AtomicBoolean(true);

    public RtpVxmlMedia(RtpMediaSession mediaSession) {
        this.mediaSession = Objects.requireNonNull(mediaSession, "mediaSession cannot be null");
    }

    @Override
    public void playAudio(byte[] pcmAudio, boolean bargeIn, Runnable onFinished) {
        this.bargeInAllowed.set(bargeIn);
        stopAudio();
        if (pcmAudio == null || pcmAudio.length == 0) {
            if (onFinished != null) {
                onFinished.run();
            }
            return;
        }
        mediaSession.playAudio(pcmAudio, onFinished);
    }

    @Override
    public void stopAudio() {
        mediaSession.stopAudio();
    }

    @Override
    public boolean isAudioPlaying() {
        return mediaSession.isAudioPlaying();
    }

    @Override
    public boolean isBargeInAllowed() {
        return bargeInAllowed.get();
    }

    public RtpMediaSession getMediaSession() {
        return mediaSession;
    }
}
