package net.pilgrim.vxml.grammar.srgs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents an ordered sequence of SRGS nodes matched sequentially.
 */
public class SrgsSequence implements SrgsNode {

    private final List<SrgsNode> nodes;

    public SrgsSequence(List<SrgsNode> nodes) {
        this.nodes = nodes != null ? new ArrayList<>(nodes) : new ArrayList<>();
    }

    public List<SrgsNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    @Override
    public String toString() {
        return "SrgsSequence{" + nodes + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SrgsSequence that)) return false;
        return Objects.equals(nodes, that.nodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes);
    }
}
