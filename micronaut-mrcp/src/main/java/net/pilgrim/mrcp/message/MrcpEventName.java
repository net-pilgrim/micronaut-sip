package net.pilgrim.mrcp.message;

/**
 * Standard MRCPv2 event names defined in RFC 6787 for Speech Synthesizer and Recognizer resources.
 */
public enum MrcpEventName {
    // Speech Synthesizer Events (RFC 6787 §8)
    SPEECH_MARKER("SPEECH-MARKER"),
    SPEAK_COMPLETE("SPEAK-COMPLETE"),

    // Speech Recognizer Events (RFC 6787 §9)
    START_OF_INPUT("START-OF-INPUT"),
    RECOGNITION_COMPLETE("RECOGNITION-COMPLETE"),
    INTERPRETATION_COMPLETE("INTERPRETATION-COMPLETE");

    private final String text;

    MrcpEventName(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public static MrcpEventName fromString(String str) {
        if (str == null) {
            return null;
        }
        String normalized = str.trim().toUpperCase(java.util.Locale.ROOT);
        for (MrcpEventName e : values()) {
            if (e.text.equals(normalized) || e.name().equals(normalized)) {
                return e;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return text;
    }
}
