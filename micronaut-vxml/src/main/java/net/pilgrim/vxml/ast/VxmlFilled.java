package net.pilgrim.vxml.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VoiceXML {@code <filled mode="all|any" namelist="...">} action block.
 */
public class VxmlFilled implements VxmlExecutable {

    private final String mode;
    private final List<String> namelist;
    private final List<VxmlExecutable> executables;

    public VxmlFilled(String mode, List<String> namelist, List<VxmlExecutable> executables) {
        this.mode = mode != null ? mode.trim() : "all";
        this.namelist = namelist != null ? new ArrayList<>(namelist) : new ArrayList<>();
        this.executables = executables != null ? new ArrayList<>(executables) : new ArrayList<>();
    }

    public String getMode() {
        return mode;
    }

    public List<String> getNamelist() {
        return Collections.unmodifiableList(namelist);
    }

    public List<VxmlExecutable> getExecutables() {
        return Collections.unmodifiableList(executables);
    }

    @Override
    public String toString() {
        return "VxmlFilled{mode='" + mode + "', namelist=" + namelist + ", executables=" + executables + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlFilled that)) return false;
        return Objects.equals(mode, that.mode) && Objects.equals(namelist, that.namelist) && Objects.equals(executables, that.executables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, namelist, executables);
    }
}
