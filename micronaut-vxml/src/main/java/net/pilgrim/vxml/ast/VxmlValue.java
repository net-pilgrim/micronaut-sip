package net.pilgrim.vxml.ast;

import java.util.Objects;

/**
 * VoiceXML {@code <value expr="..."/>} element.
 */
public class VxmlValue implements VxmlPromptContent, VxmlExecutable {

    private final String expr;

    public VxmlValue(String expr) {
        this.expr = expr != null ? expr.trim() : "";
    }

    public String getExpr() {
        return expr;
    }

    @Override
    public String toString() {
        return "VxmlValue{expr='" + expr + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlValue vxmlValue)) return false;
        return Objects.equals(expr, vxmlValue.expr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(expr);
    }
}
