package net.pilgrim.mrcp.resource;

import java.util.Locale;

/**
 * MRCPv2 resource types defined in RFC 6787.
 */
public enum MrcpResourceType {
    SPEECHSYNTH("speechsynth"),
    SPEECHRECOG("speechrecog"),
    RECORDER("recorder"),
    VERIFIER("verifier");

    private final String value;

    MrcpResourceType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MrcpResourceType fromString(String str) {
        if (str == null) return null;
        String val = str.trim().toLowerCase(Locale.ROOT);
        for (MrcpResourceType r : values()) {
            if (r.value.equals(val) || r.name().equalsIgnoreCase(val)) {
                return r;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}
