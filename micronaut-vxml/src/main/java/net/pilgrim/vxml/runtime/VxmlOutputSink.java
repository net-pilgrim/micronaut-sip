package net.pilgrim.vxml.runtime;

/**
 * Output sink interface for VoiceXML session interactions (audio output, call termination).
 */
public interface VxmlOutputSink {

    /**
     * Plays audio data over RTP or media pipeline.
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
     */
    boolean isAudioPlaying();

    /**
     * Indicates whether the currently playing prompt allows barge-in.
     */
    boolean isBargeInAllowed();

    /**
     * Called when the VoiceXML dialog completes or exits, triggering in-dialog BYE teardown.
     */
    void onDialogComplete();
}
