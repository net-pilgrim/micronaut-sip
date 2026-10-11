package net.pilgrim.mrcp.message;

/**
 * MRCP protocol version specification (RFC 6787).
 */
public enum MrcpVersion {
    MRCP_2_0("MRCP/2.0");

    private final String value;

    MrcpVersion(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MrcpVersion fromString(String val) {
        if (val == null) {
            return MRCP_2_0;
        }
        val = val.trim();
        if ("MRCP/2.0".equalsIgnoreCase(val) || "2.0".equalsIgnoreCase(val)) {
            return MRCP_2_0;
        }
        throw new IllegalArgumentException("Unsupported MRCP version: " + val);
    }

    @Override
    public String toString() {
        return value;
    }
}
