package net.pilgrim.vxml.grammar.srgs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a choice between alternative nodes (&lt;one-of&gt; in XML, | in ABNF).
 */
public class SrgsOneOf implements SrgsNode {

    private final List<SrgsNode> items;

    public SrgsOneOf(List<SrgsNode> items) {
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    public List<SrgsNode> getItems() {
        return Collections.unmodifiableList(items);
    }

    @Override
    public String toString() {
        return "SrgsOneOf{" + items + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SrgsOneOf that)) return false;
        return Objects.equals(items, that.items);
    }

    @Override
    public int hashCode() {
        return Objects.hash(items);
    }
}
