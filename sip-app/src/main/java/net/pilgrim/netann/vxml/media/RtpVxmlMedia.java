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

    @Override
    public void setDtmfListener(java.util.function.Consumer<Character> listener) {
        mediaSession.addDtmfListener(listener);
    }

    @Override
    public void startRecording() {
        mediaSession.startRecording();
    }

    @Override
    public byte[] stopRecording() {
        net.pilgrim.sip.rtp.media.AudioRecording recording = mediaSession.stopRecording();
        return recording != null ? recording.getPcmData() : new byte[0];
    }

    @Override
    public boolean isRecording() {
        return mediaSession.isRecording();
    }

    @Override
    public void sendDtmf(char digit) {
        mediaSession.sendDtmf(digit).subscribe();
    }

    /**
     * Stops the active recording and returns the full {@link net.pilgrim.sip.rtp.media.AudioRecording} metadata.
     */
    public net.pilgrim.sip.rtp.media.AudioRecording stopAudioRecording() {
        return mediaSession.stopRecording();
    }

    public RtpMediaSession getMediaSession() {
        return mediaSession;
    }
}
