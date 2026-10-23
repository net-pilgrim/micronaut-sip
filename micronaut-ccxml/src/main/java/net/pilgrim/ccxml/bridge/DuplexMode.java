package net.pilgrim.ccxml.bridge;

/**
 * Audio duplex mode for joining audio streams in CCXML.
 */
public enum DuplexMode {
    /**
     * Bidirectional full-duplex audio stream. Both participants hear each other.
     */
    FULL,

    /**
     * Unidirectional half-duplex audio stream. Media flows from id1 to id2 only (or vice versa according to CCXML spec).
     */
    HALF;

    public static DuplexMode fromString(String val) {
        if (val == null || val.isBlank()) {
            return FULL;
        }
        return switch (val.trim().toLowerCase()) {
            case "half" -> HALF;
            default -> FULL;
        };
    }
}
