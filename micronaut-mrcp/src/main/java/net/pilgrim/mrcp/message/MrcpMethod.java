package net.pilgrim.mrcp.message;

/**
 * Standard MRCPv2 methods defined in RFC 6787 for Speech Synthesizer and Recognizer resources.
 */
public enum MrcpMethod {
    // Generic / Common Methods
    SET_PARAMS("SET-PARAMS"),
    GET_PARAMS("GET-PARAMS"),

    // Speech Synthesizer Methods (RFC 6787 §8)
    SPEAK("SPEAK"),
    STOP("STOP"),
    PAUSE("PAUSE"),
    RESUME("RESUME"),
    BARGE_IN_OCCURRED("BARGE-IN-OCCURRED"),
    CONTROL("CONTROL"),
    DEFINE_LEXICON("DEFINE-LEXICON"),

    // Speech Recognizer Methods (RFC 6787 §9)
    DEFINE_GRAMMAR("DEFINE-GRAMMAR"),
    RECOGNIZE("RECOGNIZE"),
    INTERPRET("INTERPRET"),
    GET_RESULT("GET-RESULT"),
    START_INPUT_TIMERS("START-INPUT-TIMERS");

    private final String text;

    MrcpMethod(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public static MrcpMethod fromString(String str) {
        if (str == null) {
            return null;
        }
        String normalized = str.trim().toUpperCase(java.util.Locale.ROOT);
        for (MrcpMethod m : values()) {
            if (m.text.equals(normalized) || m.name().equals(normalized)) {
                return m;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return text;
    }
}
