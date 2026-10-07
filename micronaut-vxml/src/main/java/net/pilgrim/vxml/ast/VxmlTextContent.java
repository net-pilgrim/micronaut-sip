package net.pilgrim.vxml.ast;

import java.util.Objects;

/**
 * Text content inside a {@code <prompt>}.
 */
public class VxmlTextContent implements VxmlPromptContent {

    private final String text;

    public VxmlTextContent(String text) {
        this.text = text != null ? text.trim() : "";
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return "VxmlTextContent{text='" + text + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlTextContent that)) return false;
        return Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }
}
