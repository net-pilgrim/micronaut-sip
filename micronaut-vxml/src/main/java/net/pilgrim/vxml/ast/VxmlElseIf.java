package net.pilgrim.vxml.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VoiceXML {@code <elseif cond="..."/>} branch container.
 */
public class VxmlElseIf implements VxmlNode {

    private final String cond;
    private final List<VxmlExecutable> executables;

    public VxmlElseIf(String cond, List<VxmlExecutable> executables) {
        this.cond = Objects.requireNonNull(cond, "cond cannot be null").trim();
        this.executables = executables != null ? new ArrayList<>(executables) : new ArrayList<>();
    }

    public String getCond() {
        return cond;
    }

    public List<VxmlExecutable> getExecutables() {
        return Collections.unmodifiableList(executables);
    }

    @Override
    public String toString() {
        return "VxmlElseIf{cond='" + cond + "', executables=" + executables + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlElseIf that)) return false;
        return Objects.equals(cond, that.cond) && Objects.equals(executables, that.executables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cond, executables);
    }
}
