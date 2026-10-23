package net.pilgrim.vxml.grammar.srgs;

import java.util.Objects;

/**
 * Represents a literal token or phrase in an SRGS grammar (e.g. &lt;token&gt;yes&lt;/token&gt; or plain text).
 */
public class SrgsToken implements SrgsNode {

    private final String text;

    public SrgsToken(String text) {
        this.text = text != null ? text.trim() : "";
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return "SrgsToken{'" + text + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SrgsToken that)) return false;
        return Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }
}
