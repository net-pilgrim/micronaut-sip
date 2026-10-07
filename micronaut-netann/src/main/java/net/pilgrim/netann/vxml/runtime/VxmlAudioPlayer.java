package net.pilgrim.netann.vxml.runtime;

import net.pilgrim.sip.rtp.media.RtpAudioPlayer;
import net.pilgrim.sip.rtp.media.RtpMediaSession;

/**
 * Streams prompt audio over an active RtpMediaSession.
 *
 * @deprecated Prefer using {@link RtpAudioPlayer} directly from {@code :micronaut-rtp}.
 */
@Deprecated
public class VxmlAudioPlayer extends RtpAudioPlayer {

    public VxmlAudioPlayer(RtpMediaSession mediaSession, byte[] audioData, Runnable onComplete) {
        super(mediaSession, audioData, onComplete);
    }
}
