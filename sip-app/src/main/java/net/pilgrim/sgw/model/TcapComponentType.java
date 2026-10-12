package net.pilgrim.sgw.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Locale;

/**
 * TCAP Component sublayer types (ITU-T Q.772 / Q.773).
 */
@Serdeable
@Introspected
public enum TcapComponentType {
    INVOKE,
    RETURN_RESULT_LAST,
    RETURN_RESULT_NOT_LAST,
    RETURN_ERROR,
    REJECT;

    public static TcapComponentType fromString(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        String normalized = val.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (normalized.equals("INVOKE")) {
            return INVOKE;
        }
        if (normalized.equals("RETURN_RESULT") || normalized.equals("RESULT") || normalized.equals("RETURN_RESULT_LAST")) {
            return RETURN_RESULT_LAST;
        }
        if (normalized.equals("RETURN_RESULT_NOT_LAST")) {
            return RETURN_RESULT_NOT_LAST;
        }
        if (normalized.equals("RETURN_ERROR") || normalized.equals("ERROR")) {
            return RETURN_ERROR;
        }
        if (normalized.equals("REJECT")) {
            return REJECT;
        }
        for (TcapComponentType type : values()) {
            if (type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown TCAP component type: " + val);
    }
}
