package net.pilgrim.msml.model.moml;

import java.util.List;

/**
 * Encapsulates a sequence of MOML media elements executed sequentially inside a &lt;dialogstart&gt;.
 */
public record MomlDialog(List<MomlElement> elements) {

    public MomlDialog {
        elements = elements != null ? List.copyOf(elements) : List.of();
    }

    public static MomlDialog of(MomlElement... elements) {
        return new MomlDialog(List.of(elements));
    }

    public static MomlDialog of(List<MomlElement> elements) {
        return new MomlDialog(elements);
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public int size() {
        return elements.size();
    }
}
