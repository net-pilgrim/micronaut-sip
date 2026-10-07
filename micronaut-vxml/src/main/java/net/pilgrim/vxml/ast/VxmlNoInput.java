package net.pilgrim.vxml.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VoiceXML {@code <noinput count="...">} event handler.
 */
public class VxmlNoInput implements VxmlExecutable {

    private final int count;
    private final List<VxmlExecutable> executables;

    public VxmlNoInput(int count, List<VxmlExecutable> executables) {
        this.count = count > 0 ? count : 1;
        this.executables = executables != null ? new ArrayList<>(executables) : new ArrayList<>();
    }

    public int getCount() {
        return count;
    }

    public List<VxmlExecutable> getExecutables() {
        return Collections.unmodifiableList(executables);
    }

    @Override
    public String toString() {
        return "VxmlNoInput{count=" + count + ", executables=" + executables + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlNoInput that)) return false;
        return count == that.count && Objects.equals(executables, that.executables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(count, executables);
    }
}
