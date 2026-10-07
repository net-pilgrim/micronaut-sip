package net.pilgrim.vxml.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VoiceXML {@code <block name="..." cond="...">} form item.
 */
public class VxmlBlock implements VxmlFormItem {

    private final String name;
    private final String cond;
    private final List<VxmlExecutable> executables;

    public VxmlBlock(String name, String cond, List<VxmlExecutable> executables) {
        this.name = name != null ? name.trim() : null;
        this.cond = cond != null ? cond.trim() : null;
        this.executables = executables != null ? new ArrayList<>(executables) : new ArrayList<>();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getCond() {
        return cond;
    }

    public List<VxmlExecutable> getExecutables() {
        return Collections.unmodifiableList(executables);
    }

    @Override
    public String toString() {
        return "VxmlBlock{name='" + name + "', cond='" + cond + "', executables=" + executables + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlBlock vxmlBlock)) return false;
        return Objects.equals(name, vxmlBlock.name) && Objects.equals(cond, vxmlBlock.cond) && Objects.equals(executables, vxmlBlock.executables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, cond, executables);
    }
}
