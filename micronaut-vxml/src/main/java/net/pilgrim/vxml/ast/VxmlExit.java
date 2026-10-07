package net.pilgrim.vxml.ast;

import java.util.Objects;

/**
 * VoiceXML {@code <exit expr="..." namelist="..."/>} element.
 */
public class VxmlExit implements VxmlExecutable {

    private final String expr;
    private final String namelist;

    public VxmlExit(String expr, String namelist) {
        this.expr = expr != null ? expr.trim() : null;
        this.namelist = namelist != null ? namelist.trim() : null;
    }

    public String getExpr() {
        return expr;
    }

    public String getNamelist() {
        return namelist;
    }

    @Override
    public String toString() {
        return "VxmlExit{expr='" + expr + "', namelist='" + namelist + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlExit vxmlExit)) return false;
        return Objects.equals(expr, vxmlExit.expr) && Objects.equals(namelist, vxmlExit.namelist);
    }

    @Override
    public int hashCode() {
        return Objects.hash(expr, namelist);
    }
}
