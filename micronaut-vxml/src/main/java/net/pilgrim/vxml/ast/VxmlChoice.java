package net.pilgrim.vxml.ast;

import java.util.Objects;

/**
 * VoiceXML {@code <choice dtmf="..." next="..." expr="..." event="...">} element.
 */
public class VxmlChoice implements VxmlNode {

    private final String dtmf;
    private final String next;
    private final String expr;
    private final String event;
    private final String text;

    public VxmlChoice(String dtmf, String next, String expr, String event, String text) {
        this.dtmf = dtmf != null ? dtmf.trim() : null;
        this.next = next != null ? next.trim() : null;
        this.expr = expr != null ? expr.trim() : null;
        this.event = event != null ? event.trim() : null;
        this.text = text != null ? text.trim() : "";
    }

    public String getDtmf() {
        return dtmf;
    }

    public String getNext() {
        return next;
    }

    public String getExpr() {
        return expr;
    }

    public String getEvent() {
        return event;
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return "VxmlChoice{dtmf='" + dtmf + "', next='" + next + "', expr='" + expr + "', event='" + event + "', text='" + text + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlChoice that)) return false;
        return Objects.equals(dtmf, that.dtmf) && Objects.equals(next, that.next) && Objects.equals(expr, that.expr) && Objects.equals(event, that.event) && Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dtmf, next, expr, event, text);
    }
}
