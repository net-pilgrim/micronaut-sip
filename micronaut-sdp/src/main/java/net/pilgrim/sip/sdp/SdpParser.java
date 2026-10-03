package net.pilgrim.sip.sdp;

import java.util.ArrayList;
import java.util.List;

/**
 * Basic SDP parser that supports session-level fields, media sections and attributes.
 */
public final class SdpParser {

    public SdpMessage parse(String sdpText) {
        if (sdpText == null || sdpText.isBlank()) {
            throw new IllegalArgumentException("SDP text must not be empty");
        }

        SdpMessage message = new SdpMessage();
        SdpMessage.MediaDescription currentMedia = null;

        String[] lines = sdpText.split("\\r?\\n");
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty() || line.length() < 2 || line.charAt(1) != '=') {
                continue;
            }

            char type = line.charAt(0);
            String value = line.substring(2).trim();
            switch (type) {
                case 'v' -> message.setVersion(value);
                case 'o' -> message.setOrigin(value);
                case 's' -> message.setSessionName(value);
                case 'c' -> message.setConnection(value);
                case 't' -> message.setTiming(value);
                case 'm' -> {
                    currentMedia = parseMediaLine(value);
                    message.addMediaDescription(currentMedia);
                }
                case 'a' -> {
                    if (currentMedia != null) {
                        currentMedia.addAttribute(value);
                    } else {
                        message.addSessionAttribute(value);
                    }
                }
                default -> {
                    // Ignore unsupported/unused lines in this basic implementation.
                }
            }
        }

        return message;
    }

    private SdpMessage.MediaDescription parseMediaLine(String value) {
        String[] parts = value.split("\\s+");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid SDP media line: " + value);
        }
        String media = parts[0].trim();
        int port;
        try {
            port = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid SDP media port: " + value, e);
        }
        String protocol = parts[2].trim();
        List<String> formats = new ArrayList<>();
        for (int i = 3; i < parts.length; i++) {
            String fmt = parts[i].trim();
            if (!fmt.isEmpty()) {
                formats.add(fmt);
            }
        }
        return new SdpMessage.MediaDescription(media, port, protocol, formats);
    }
}
