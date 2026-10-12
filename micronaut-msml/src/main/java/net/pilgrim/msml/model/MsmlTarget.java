package net.pilgrim.msml.model;

import java.util.Objects;

/**
 * Represents an MSML target addressing a connection, conference bridge, or dialog (RFC 5707 Section 2).
 * <p>
 * Supported formats:
 * <ul>
 *   <li>{@code conn:<connection-id>} - identifies a point-to-point media stream / SIP dialog</li>
 *   <li>{@code conf:<conference-id>} - identifies a multi-party conference mixer</li>
 *   <li>{@code dialog:<dialog-id>} - identifies an active dialog instance</li>
 * </ul>
 */
public record MsmlTarget(Type type, String id) {

    public enum Type {
        CONNECTION("conn"),
        CONFERENCE("conf"),
        DIALOG("dialog");

        private final String prefix;

        Type(String prefix) {
            this.prefix = prefix;
        }

        public String getPrefix() {
            return prefix;
        }
    }

    public MsmlTarget {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }

    public static MsmlTarget connection(String id) {
        return new MsmlTarget(Type.CONNECTION, sanitize(id));
    }

    public static MsmlTarget conference(String id) {
        return new MsmlTarget(Type.CONFERENCE, sanitize(id));
    }

    public static MsmlTarget dialog(String id) {
        return new MsmlTarget(Type.DIALOG, sanitize(id));
    }

    public static MsmlTarget parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Target string must not be blank");
        }
        String trimmed = raw.trim();
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx > 0) {
            String prefix = trimmed.substring(0, colonIdx).toLowerCase();
            String id = trimmed.substring(colonIdx + 1).trim();
            for (Type t : Type.values()) {
                if (t.prefix.equalsIgnoreCase(prefix)) {
                    return new MsmlTarget(t, id);
                }
            }
        }
        // Default to connection target if no prefix specified
        return new MsmlTarget(Type.CONNECTION, trimmed);
    }

    private static String sanitize(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Target ID must not be blank");
        }
        String trimmed = id.trim();
        int colonIdx = trimmed.indexOf(':');
        return colonIdx >= 0 ? trimmed.substring(colonIdx + 1).trim() : trimmed;
    }

    public boolean isConnection() {
        return type == Type.CONNECTION;
    }

    public boolean isConference() {
        return type == Type.CONFERENCE;
    }

    public boolean isDialog() {
        return type == Type.DIALOG;
    }

    @Override
    public String toString() {
        return type.prefix + ":" + id;
    }
}
