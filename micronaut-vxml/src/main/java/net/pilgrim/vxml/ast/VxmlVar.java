package net.pilgrim.vxml.ast;

import java.util.Objects;

/**
 * VoiceXML {@code <var name="..." expr="..."/>} element.
 */
public class VxmlVar implements VxmlExecutable, VxmlFormItem {

    private final String name;
    private final String expr;

    public VxmlVar(String name, String expr) {
        this.name = Objects.requireNonNull(name, "name cannot be null").trim();
        this.expr = expr != null ? expr.trim() : null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getCond() {
        return null;
    }

    public String getExpr() {
        return expr;
    }

    @Override
    public String toString() {
        return "VxmlVar{name='" + name + "', expr='" + expr + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlVar vxmlVar)) return false;
        return Objects.equals(name, vxmlVar.name) && Objects.equals(expr, vxmlVar.expr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, expr);
    }
}
