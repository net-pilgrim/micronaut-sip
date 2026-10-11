package net.pilgrim.mrcp.resource;

import java.util.Objects;
import java.util.UUID;

/**
 * MRCP Channel Identifier representing {@code <channel-id>@<resource-type>} per RFC 6787 §6.2.1.
 */
public final class MrcpChannelId {

    private final String id;
    private final MrcpResourceType resourceType;

    public MrcpChannelId(String id, MrcpResourceType resourceType) {
        this.id = Objects.requireNonNull(id, "id must not be null").trim();
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType must not be null");
    }

    public static MrcpChannelId generate(MrcpResourceType resourceType) {
        return new MrcpChannelId(UUID.randomUUID().toString(), resourceType);
    }

    public static MrcpChannelId parse(String str) {
        if (str == null || str.isBlank()) {
            throw new IllegalArgumentException("Channel-Identifier string cannot be blank");
        }
        int atIdx = str.indexOf('@');
        if (atIdx <= 0 || atIdx == str.length() - 1) {
            throw new IllegalArgumentException("Invalid Channel-Identifier format, expected <id>@<resource-type>: " + str);
        }
        String idPart = str.substring(0, atIdx).trim();
        String typePart = str.substring(atIdx + 1).trim();
        MrcpResourceType type = MrcpResourceType.fromString(typePart);
        if (type == null) {
            throw new IllegalArgumentException("Unknown MRCP resource type in Channel-Identifier: " + typePart);
        }
        return new MrcpChannelId(idPart, type);
    }

    public String getId() {
        return id;
    }

    public MrcpResourceType getResourceType() {
        return resourceType;
    }

    @Override
    public String toString() {
        return id + "@" + resourceType.getValue();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MrcpChannelId that = (MrcpChannelId) o;
        return Objects.equals(id, that.id) && resourceType == that.resourceType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, resourceType);
    }
}
