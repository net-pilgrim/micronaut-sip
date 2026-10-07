package net.pilgrim.vxml.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VoiceXML {@code <if cond="...">} element with optional {@code <elseif>} and {@code <else>} branches.
 */
public class VxmlIf implements VxmlExecutable {

    private final String cond;
    private final List<VxmlExecutable> thenExecutables;
    private final List<VxmlElseIf> elseIfs;
    private final List<VxmlExecutable> elseExecutables;

    public VxmlIf(String cond,
                  List<VxmlExecutable> thenExecutables,
                  List<VxmlElseIf> elseIfs,
                  List<VxmlExecutable> elseExecutables) {
        this.cond = Objects.requireNonNull(cond, "cond cannot be null").trim();
        this.thenExecutables = thenExecutables != null ? new ArrayList<>(thenExecutables) : new ArrayList<>();
        this.elseIfs = elseIfs != null ? new ArrayList<>(elseIfs) : new ArrayList<>();
        this.elseExecutables = elseExecutables != null ? new ArrayList<>(elseExecutables) : new ArrayList<>();
    }

    public String getCond() {
        return cond;
    }

    public List<VxmlExecutable> getThenExecutables() {
        return Collections.unmodifiableList(thenExecutables);
    }

    public List<VxmlElseIf> getElseIfs() {
        return Collections.unmodifiableList(elseIfs);
    }

    public List<VxmlExecutable> getElseExecutables() {
        return Collections.unmodifiableList(elseExecutables);
    }

    @Override
    public String toString() {
        return "VxmlIf{cond='" + cond + "', then=" + thenExecutables + ", elseIfs=" + elseIfs + ", else=" + elseExecutables + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlIf vxmlIf)) return false;
        return Objects.equals(cond, vxmlIf.cond)
                && Objects.equals(thenExecutables, vxmlIf.thenExecutables)
                && Objects.equals(elseIfs, vxmlIf.elseIfs)
                && Objects.equals(elseExecutables, vxmlIf.elseExecutables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cond, thenExecutables, elseIfs, elseExecutables);
    }
}
