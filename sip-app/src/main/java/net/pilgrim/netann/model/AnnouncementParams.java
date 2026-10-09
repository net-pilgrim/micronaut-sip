package net.pilgrim.netann.model;

import io.micronaut.core.annotation.Introspected;
import net.pilgrim.sip.model.SipUri;

import java.util.Objects;

/**
 * Models the parsed announcement parameters from an RFC 4240 Request-URI:
 * sip:annc@ms.example.net;play=<url>[;repeat=N|forever][;delay=ms][;duration=ms][;locale=xx][;content-type=type]
 */
@Introspected
public class AnnouncementParams {

    public static final int REPEAT_FOREVER = -1;

    private final String play;
    private final int repeat;
    private final long delayMs;
    private final long durationMs;
    private final String locale;
    private final String contentType;

    public AnnouncementParams(String play, int repeat, long delayMs, long durationMs, String locale, String contentType) {
        this.play = play;
        this.repeat = repeat;
        this.delayMs = delayMs;
        this.durationMs = durationMs;
        this.locale = locale;
        this.contentType = contentType;
    }

    public static AnnouncementParams parse(SipUri uri) {
        if (uri == null) {
            return new AnnouncementParams(null, 1, 0, 0, null, null);
        }

        String play = uri.getParameter("play");
        int repeat = 1;
        String repeatStr = uri.getParameter("repeat");
        if (repeatStr != null && !repeatStr.isBlank()) {
            if ("forever".equalsIgnoreCase(repeatStr.trim())) {
                repeat = REPEAT_FOREVER;
            } else {
                try {
                    repeat = Math.max(1, Integer.parseInt(repeatStr.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid repeat parameter: " + repeatStr);
                }
            }
        }

        long delayMs = 0;
        String delayStr = uri.getParameter("delay");
        if (delayStr != null && !delayStr.isBlank()) {
            try {
                delayMs = Math.max(0, Long.parseLong(delayStr.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid delay parameter: " + delayStr);
            }
        }

        long durationMs = 0;
        String durationStr = uri.getParameter("duration");
        if (durationStr != null && !durationStr.isBlank()) {
            try {
                durationMs = Math.max(0, Long.parseLong(durationStr.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid duration parameter: " + durationStr);
            }
        }

        String locale = uri.getParameter("locale");
        String contentType = uri.getParameter("content-type");

        return new AnnouncementParams(play, repeat, delayMs, durationMs, locale, contentType);
    }

    public String getPlay() {
        return play;
    }

    public int getRepeat() {
        return repeat;
    }

    public boolean isRepeatForever() {
        return repeat == REPEAT_FOREVER;
    }

    public long getDelayMs() {
        return delayMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getLocale() {
        return locale;
    }

    public String getContentType() {
        return contentType;
    }

    @Override
    public String toString() {
        return "AnnouncementParams{" +
                "play='" + play + '\'' +
                ", repeat=" + (repeat == REPEAT_FOREVER ? "forever" : repeat) +
                ", delayMs=" + delayMs +
                ", durationMs=" + durationMs +
                ", locale='" + locale + '\'' +
                ", contentType='" + contentType + '\'' +
                '}';
    }
}
