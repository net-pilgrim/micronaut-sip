package net.pilgrim.vxml.media;

import java.util.function.Consumer;

/**
 * Decoupled media interface for VoiceXML interpreter audio output, barge-in control,
 * and media-level input events.
 * <p>
 * Decouples the VoiceXML 2.1 Form Interpretation Algorithm (FIA) engine from underlying
 * transport and audio protocols (such as RTP, WebRTC, or local sound devices).
 */
public interface VxmlMedia {

    /**
     * Plays audio data over the media channel.
     *
     * @param pcmAudio   8000 Hz 16-bit mono signed PCM-16LE audio bytes
     * @param bargeIn    whether incoming DTMF can interrupt this playback
     * @param onFinished callback invoked when audio streaming completes
     */
    void playAudio(byte[] pcmAudio, boolean bargeIn, Runnable onFinished);

    /**
     * Stops any currently playing audio immediately (e.g. for barge-in or session teardown).
     */
    void stopAudio();

    /**
     * Indicates whether audio is currently playing.
     *
     * @return {@code true} if audio is actively streaming
     */
    boolean isAudioPlaying();

    /**
     * Indicates whether the currently playing prompt allows barge-in interruption.
     *
     * @return {@code true} if barge-in is permitted
     */
    boolean isBargeInAllowed();

    /**
     * Registers a listener for DTMF digits detected on the media stream.
     *
     * @param listener consumer receiving DTMF digit characters
     */
    default void setDtmfListener(Consumer<Character> listener) {
        // Optional hook for media providers detecting inband DTMF
    }

    /**
     * Starts recording audio from the media stream.
     */
    default void startRecording() {
    }

    /**
     * Stops recording and returns captured PCM-16LE audio bytes.
     *
     * @return recorded linear PCM-16LE bytes, or empty array if none
     */
    default byte[] stopRecording() {
        return new byte[0];
    }

    /**
     * Indicates whether audio recording is currently active.
     *
     * @return {@code true} if actively recording
     */
    default boolean isRecording() {
        return false;
    }

    /**
     * Sends a DTMF digit over the media stream.
     *
     * @param digit DTMF character ('0'-'9', '*', '#', 'A'-'D')
     */
    default void sendDtmf(char digit) {
    }

    /**
     * A no-op media implementation that immediately fires completion callbacks.
     */
    VxmlMedia NOOP = new VxmlMedia() {
        @Override
        public void playAudio(byte[] pcmAudio, boolean bargeIn, Runnable onFinished) {
            if (onFinished != null) {
                onFinished.run();
            }
        }

        @Override
        public void stopAudio() {
        }

        @Override
        public boolean isAudioPlaying() {
            return false;
        }

        @Override
        public boolean isBargeInAllowed() {
            return true;
        }
    };

    /**
     * Returns a no-op media implementation.
     *
     * @return static no-op media object
     */
    static VxmlMedia noop() {
        return NOOP;
    }
}
