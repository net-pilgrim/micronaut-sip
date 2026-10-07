package net.pilgrim.vxml.ast;

import java.util.Objects;

/**
 * VoiceXML {@code <assign name="..." expr="..."/>} element.
 */
public class VxmlAssign implements VxmlExecutable {

    private final String name;
    private final String expr;

    public VxmlAssign(String name, String expr) {
        this.name = Objects.requireNonNull(name, "name cannot be null").trim();
        this.expr = expr != null ? expr.trim() : null;
    }

    public String getName() {
        return name;
    }

    public String getExpr() {
        return expr;
    }

    @Override
    public String toString() {
        return "VxmlAssign{name='" + name + "', expr='" + expr + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlAssign that)) return false;
        return Objects.equals(name, that.name) && Objects.equals(expr, that.expr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, expr);
    }
}
