package net.pilgrim.sip.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * Standard SIP methods as defined in RFC 3261 and related RFCs.
 */
public enum SipMethod {
    INVITE,
    ACK,
    BYE,
    CANCEL,
    OPTIONS,
    REGISTER,
    MESSAGE,   // RFC 3428
    INFO,      // RFC 2976 / RFC 6086
    PRACK,     // RFC 3262
    SUBSCRIBE, // RFC 6665
    NOTIFY,    // RFC 6665
    REFER,     // RFC 3515
    UPDATE;    // RFC 3311

    public static SipMethod from(String methodName) {
        if (methodName == null) {
            throw new IllegalArgumentException("Method name cannot be null");
        }
        String upper = methodName.trim().toUpperCase(Locale.ROOT);
        for (SipMethod method : values()) {
            if (method.name().equals(upper)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Unsupported SIP method: " + methodName);
    }

    public static boolean isStandard(String methodName) {
        if (methodName == null) return false;
        String upper = methodName.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).anyMatch(m -> m.name().equals(upper));
    }
}
