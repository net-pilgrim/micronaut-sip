package net.pilgrim.vxml.ast;

import java.util.Objects;

/**
 * VoiceXML {@code <goto next="..." expr="..." nextitem="..."/>} element.
 */
public class VxmlGoto implements VxmlExecutable {

    private final String next;
    private final String expr;
    private final String nextItem;

    public VxmlGoto(String next, String expr, String nextItem) {
        this.next = next != null ? next.trim() : null;
        this.expr = expr != null ? expr.trim() : null;
        this.nextItem = nextItem != null ? nextItem.trim() : null;
    }

    public String getNext() {
        return next;
    }

    public String getExpr() {
        return expr;
    }

    public String getNextItem() {
        return nextItem;
    }

    @Override
    public String toString() {
        return "VxmlGoto{next='" + next + "', expr='" + expr + "', nextItem='" + nextItem + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlGoto vxmlGoto)) return false;
        return Objects.equals(next, vxmlGoto.next) && Objects.equals(expr, vxmlGoto.expr) && Objects.equals(nextItem, vxmlGoto.nextItem);
    }

    @Override
    public int hashCode() {
        return Objects.hash(next, expr, nextItem);
    }
}
