package net.pilgrim.vxml.grammar.srgs;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a rule definition within an SRGS grammar (&lt;rule id="..." scope="..."&gt;).
 */
public class SrgsRule implements Serializable {

    private final String id;
    private final boolean isPublic;
    private final SrgsNode node;

    public SrgsRule(String id, boolean isPublic, SrgsNode node) {
        this.id = Objects.requireNonNull(id, "rule id must not be null").trim();
        this.isPublic = isPublic;
        this.node = node;
    }

    public String getId() {
        return id;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public SrgsNode getNode() {
        return node;
    }

    @Override
    public String toString() {
        return "SrgsRule{id='" + id + "', public=" + isPublic + ", node=" + node + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SrgsRule that)) return false;
        return isPublic == that.isPublic && Objects.equals(id, that.id) && Objects.equals(node, that.node);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, isPublic, node);
    }
}
