package net.pilgrim.mrcp.message;

/**
 * MRCPv2 request state in responses and events (RFC 6787 §5.2, §5.3).
 */
public enum MrcpRequestState {
    COMPLETE("COMPLETE"),
    IN_PROGRESS("IN-PROGRESS"),
    PENDING("PENDING");

    private final String text;

    MrcpRequestState(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public static MrcpRequestState fromString(String str) {
        if (str == null) {
            return COMPLETE;
        }
        String normalized = str.trim().toUpperCase(java.util.Locale.ROOT);
        for (MrcpRequestState state : values()) {
            if (state.text.equals(normalized) || state.name().equals(normalized)) {
                return state;
            }
        }
        return COMPLETE;
    }

    @Override
    public String toString() {
        return text;
    }
}
