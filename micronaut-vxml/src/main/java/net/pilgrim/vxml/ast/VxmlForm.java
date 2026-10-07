package net.pilgrim.vxml.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VoiceXML {@code <form id="...">} dialog container.
 */
public class VxmlForm implements VxmlNode {

    private final String id;
    private final List<VxmlFormItem> items;

    public VxmlForm(String id, List<VxmlFormItem> items) {
        this.id = id != null ? id.trim() : null;
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public List<VxmlFormItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    @Override
    public String toString() {
        return "VxmlForm{id='" + id + "', items=" + items + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlForm vxmlForm)) return false;
        return Objects.equals(id, vxmlForm.id) && Objects.equals(items, vxmlForm.items);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, items);
    }
}
