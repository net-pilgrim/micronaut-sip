package net.pilgrim.sip.sdp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Minimal SDP model for basic offer/answer handling.
 */
public final class SdpMessage {

    private String version = "0";
    private String origin = "- 0 0 IN IP4 127.0.0.1";
    private String sessionName = "Call";
    private String connection = "IN IP4 127.0.0.1";
    private String timing = "0 0";
    private final List<String> sessionAttributes = new ArrayList<>();
    private final List<MediaDescription> mediaDescriptions = new ArrayList<>();

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        if (version != null && !version.isBlank()) {
            this.version = version.trim();
        }
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        if (origin != null && !origin.isBlank()) {
            this.origin = origin.trim();
        }
    }

    public String getSessionName() {
        return sessionName;
    }

    public void setSessionName(String sessionName) {
        if (sessionName != null && !sessionName.isBlank()) {
            this.sessionName = sessionName.trim();
        }
    }

    public String getConnection() {
        return connection;
    }

    public void setConnection(String connection) {
        if (connection != null && !connection.isBlank()) {
            this.connection = connection.trim();
        }
    }

    public String getTiming() {
        return timing;
    }

    public void setTiming(String timing) {
        if (timing != null && !timing.isBlank()) {
            this.timing = timing.trim();
        }
    }

    public List<String> getSessionAttributes() {
        return Collections.unmodifiableList(sessionAttributes);
    }

    public void addSessionAttribute(String attribute) {
        if (attribute != null && !attribute.isBlank()) {
            sessionAttributes.add(attribute.trim());
        }
    }

    public List<MediaDescription> getMediaDescriptions() {
        return Collections.unmodifiableList(mediaDescriptions);
    }

    public void addMediaDescription(MediaDescription mediaDescription) {
        if (mediaDescription != null) {
            mediaDescriptions.add(mediaDescription);
        }
    }

    public MediaDescription findFirstAudioMedia() {
        for (MediaDescription media : mediaDescriptions) {
            if ("audio".equalsIgnoreCase(media.getMedia())) {
                return media;
            }
        }
        return null;
    }

    public String getDirectionAttribute() {
        return findDirection(sessionAttributes);
    }

    public String toSdpString() {
        StringBuilder sb = new StringBuilder();
        sb.append("v=").append(version).append("\r\n");
        sb.append("o=").append(origin).append("\r\n");
        sb.append("s=").append(sessionName).append("\r\n");
        sb.append("c=").append(connection).append("\r\n");
        sb.append("t=").append(timing).append("\r\n");

        for (String attr : sessionAttributes) {
            sb.append("a=").append(attr).append("\r\n");
        }

        for (MediaDescription media : mediaDescriptions) {
            sb.append("m=").append(media.getMedia()).append(' ')
                    .append(media.getPort()).append(' ')
                    .append(media.getProtocol());
            for (String fmt : media.getFormats()) {
                sb.append(' ').append(fmt);
            }
            sb.append("\r\n");
            for (String attr : media.getAttributes()) {
                sb.append("a=").append(attr).append("\r\n");
            }
        }
        return sb.toString();
    }

    private static String findDirection(List<String> attributes) {
        for (String attr : attributes) {
            String v = attr.toLowerCase(Locale.ROOT);
            if (v.equals("sendrecv") || v.equals("sendonly") || v.equals("recvonly") || v.equals("inactive")) {
                return v;
            }
        }
        return null;
    }

    public static final class MediaDescription {
        private final String media;
        private final int port;
        private final String protocol;
        private final List<String> formats = new ArrayList<>();
        private final List<String> attributes = new ArrayList<>();

        public MediaDescription(String media, int port, String protocol, List<String> formats) {
            this.media = media;
            this.port = port;
            this.protocol = protocol;
            if (formats != null) {
                this.formats.addAll(formats);
            }
        }

        public String getMedia() {
            return media;
        }

        public int getPort() {
            return port;
        }

        public String getProtocol() {
            return protocol;
        }

        public List<String> getFormats() {
            return Collections.unmodifiableList(formats);
        }

        public List<String> getAttributes() {
            return Collections.unmodifiableList(attributes);
        }

        public void addAttribute(String value) {
            if (value != null && !value.isBlank()) {
                attributes.add(value.trim());
            }
        }

        public String getDirectionAttribute() {
            return findDirection(attributes);
        }
    }
}
