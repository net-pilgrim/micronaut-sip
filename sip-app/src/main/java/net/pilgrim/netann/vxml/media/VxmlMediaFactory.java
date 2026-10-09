package net.pilgrim.netann.vxml.media;

import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.vxml.media.VxmlMedia;

/**
 * Factory for creating decoupled {@link VxmlMedia} instances for VoiceXML interpreter sessions.
 */
@FunctionalInterface
public interface VxmlMediaFactory {

    /**
     * Creates a {@link VxmlMedia} instance bound to the given RTP media session.
     *
     * @param callId       SIP Call-ID
     * @param mediaSession active RTP media session
     * @return media object to inject into the VoiceXML interpreter
     */
    VxmlMedia createMedia(String callId, RtpMediaSession mediaSession);
}
