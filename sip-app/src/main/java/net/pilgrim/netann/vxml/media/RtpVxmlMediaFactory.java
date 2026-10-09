package net.pilgrim.netann.vxml.media;

import jakarta.inject.Singleton;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.vxml.media.VxmlMedia;

/**
 * Default Micronaut singleton factory producing {@link RtpVxmlMedia} objects from {@link RtpMediaSession}.
 */
@Singleton
public class RtpVxmlMediaFactory implements VxmlMediaFactory {

    @Override
    public VxmlMedia createMedia(String callId, RtpMediaSession mediaSession) {
        return new RtpVxmlMedia(mediaSession);
    }
}
