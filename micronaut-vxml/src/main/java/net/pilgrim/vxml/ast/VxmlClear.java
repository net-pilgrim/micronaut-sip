package net.pilgrim.vxml.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VoiceXML {@code <clear namelist="..."/>} element.
 */
public class VxmlClear implements VxmlExecutable {

    private final List<String> namelist;

    public VxmlClear(List<String> namelist) {
        this.namelist = namelist != null ? new ArrayList<>(namelist) : new ArrayList<>();
    }

    public List<String> getNamelist() {
        return Collections.unmodifiableList(namelist);
    }

    @Override
    public String toString() {
        return "VxmlClear{namelist=" + namelist + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlClear vxmlClear)) return false;
        return Objects.equals(namelist, vxmlClear.namelist);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namelist);
    }
}
