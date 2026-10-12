package net.pilgrim.msml.model;

/**
 * Direction of media stream flow between connections or conferences in MSML &lt;join&gt;.
 */
public enum StreamDirection {
    SENDRECV("sendrecv"),
    SENDONLY("sendonly"),
    RECVONLY("recvonly"),
    INACTIVE("inactive");

    private final String value;

    StreamDirection(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static StreamDirection fromString(String str) {
        if (str == null || str.isBlank()) {
            return SENDRECV;
        }
        for (StreamDirection dir : values()) {
            if (dir.value.equalsIgnoreCase(str.trim())) {
                return dir;
            }
        }
        return SENDRECV;
    }
}
