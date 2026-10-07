package net.pilgrim.vxml.ast;

import java.util.Objects;

/**
 * VoiceXML {@code <audio src="..." expr="..." alt="..."/>} element.
 */
public class VxmlAudio implements VxmlPromptContent, VxmlExecutable {

    private final String src;
    private final String expr;
    private final String alt;

    public VxmlAudio(String src, String expr, String alt) {
        this.src = src != null ? src.trim() : null;
        this.expr = expr != null ? expr.trim() : null;
        this.alt = alt != null ? alt.trim() : null;
    }

    public String getSrc() {
        return src;
    }

    public String getExpr() {
        return expr;
    }

    public String getAlt() {
        return alt;
    }

    @Override
    public String toString() {
        return "VxmlAudio{src='" + src + "', expr='" + expr + "', alt='" + alt + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlAudio vxmlAudio)) return false;
        return Objects.equals(src, vxmlAudio.src) && Objects.equals(expr, vxmlAudio.expr) && Objects.equals(alt, vxmlAudio.alt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(src, expr, alt);
    }
}
