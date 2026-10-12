package net.pilgrim.sgw.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Locale;

/**
 * TCAP Dialogue / Transaction Message Types (ITU-T Q.771 - Q.775 / ANSI T1.114).
 * Supports case-insensitive normalization for SIP payloads.
 */
@Serdeable
@Introspected
public enum TcapMessageType {
    TC_BEGIN,
    TC_CONTINUE,
    TC_END,
    TC_ABORT,
    TC_UNI;

    public static TcapMessageType fromString(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        String normalized = val.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (normalized.equals("BEGIN") || normalized.equals("TC_BEGIN")) {
            return TC_BEGIN;
        }
        if (normalized.equals("CONTINUE") || normalized.equals("TC_CONTINUE")) {
            return TC_CONTINUE;
        }
        if (normalized.equals("END") || normalized.equals("TC_END")) {
            return TC_END;
        }
        if (normalized.equals("ABORT") || normalized.equals("TC_ABORT") || normalized.equals("U_ABORT") || normalized.equals("P_ABORT")) {
            return TC_ABORT;
        }
        if (normalized.equals("UNI") || normalized.equals("TC_UNI") || normalized.equals("UNIDIRECTIONAL")) {
            return TC_UNI;
        }
        for (TcapMessageType type : values()) {
            if (type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown TCAP message type: " + val);
    }
}
