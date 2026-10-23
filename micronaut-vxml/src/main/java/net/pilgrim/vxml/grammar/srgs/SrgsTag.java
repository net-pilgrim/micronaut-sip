package net.pilgrim.vxml.grammar.srgs;

import java.util.Objects;

/**
 * Represents a semantic interpretation tag (&lt;tag&gt;...&lt;/tag&gt; or {...} in ABNF).
 */
public class SrgsTag implements SrgsNode {

    private final String content;

    public SrgsTag(String content) {
        this.content = content != null ? content.trim() : "";
    }

    public String getContent() {
        return content;
    }

    @Override
    public String toString() {
        return "SrgsTag{'" + content + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SrgsTag that)) return false;
        return Objects.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content);
    }
}
