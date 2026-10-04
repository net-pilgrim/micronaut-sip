package net.pilgrim.sip.rtp.media;

/**
 * Functional hook for processing, analyzing, or observing audio frames in an RTP stream
 * (such as Goertzel inband DTMF detection, Voice Activity Detection (VAD), energy monitors,
 * AGC, or audio recording).
 */
@FunctionalInterface
public interface AudioProcessor {

    /**
     * Processes an audio frame.
     *
     * @param frame audio frame containing PCM-16LE samples and stream metadata
     */
    void process(AudioFrame frame);
}
