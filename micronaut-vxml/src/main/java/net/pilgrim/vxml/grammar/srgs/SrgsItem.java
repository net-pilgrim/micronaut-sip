package net.pilgrim.vxml.grammar.srgs;

import java.util.Objects;

/**
 * Represents an &lt;item&gt; in an SRGS grammar with repeat counts, weight, optional tag, and child node.
 */
public class SrgsItem implements SrgsNode {

    private final SrgsNode child;
    private final int minRepeat;
    private final int maxRepeat;
    private final float weight;
    private final String tag;

    public SrgsItem(SrgsNode child, int minRepeat, int maxRepeat, float weight, String tag) {
        this.child = child;
        this.minRepeat = Math.max(0, minRepeat);
        this.maxRepeat = maxRepeat >= this.minRepeat ? maxRepeat : this.minRepeat;
        this.weight = weight > 0 ? weight : 1.0f;
        this.tag = tag != null ? tag.trim() : null;
    }

    public SrgsItem(SrgsNode child) {
        this(child, 1, 1, 1.0f, null);
    }

    public SrgsItem(SrgsNode child, String tag) {
        this(child, 1, 1, 1.0f, tag);
    }

    public SrgsNode getChild() {
        return child;
    }

    public int getMinRepeat() {
        return minRepeat;
    }

    public int getMaxRepeat() {
        return maxRepeat;
    }

    public boolean isOptional() {
        return minRepeat == 0;
    }

    public float getWeight() {
        return weight;
    }

    public String getTag() {
        return tag;
    }

    @Override
    public String toString() {
        return "SrgsItem{repeat=" + minRepeat + "-" + maxRepeat + ", tag='" + tag + "', child=" + child + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SrgsItem that)) return false;
        return minRepeat == that.minRepeat && maxRepeat == that.maxRepeat && Float.compare(that.weight, weight) == 0 && Objects.equals(child, that.child) && Objects.equals(tag, that.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(child, minRepeat, maxRepeat, weight, tag);
    }
}
