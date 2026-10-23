package net.pilgrim.vxml.ast;

import java.util.Objects;

/**
 * VoiceXML {@code <grammar mode="..." type="..." src="...">} element.
 */
public class VxmlGrammar implements VxmlNode {

    private final String mode;
    private final String type;
    private final String src;
    private final String inlineContent;
    private final String root;
    private final String xmlLang;
    private final String version;

    public VxmlGrammar(String mode, String type, String src, String inlineContent) {
        this(mode, type, src, inlineContent, null, null, null);
    }

    public VxmlGrammar(String mode, String type, String src, String inlineContent, String root, String xmlLang, String version) {
        this.mode = mode != null ? mode.trim() : null;
        this.type = type != null ? type.trim() : null;
        this.src = src != null ? src.trim() : null;
        this.inlineContent = inlineContent != null ? inlineContent.trim() : null;
        this.root = root != null ? root.trim() : null;
        this.xmlLang = xmlLang != null ? xmlLang.trim() : null;
        this.version = version != null ? version.trim() : null;
    }

    public String getMode() {
        return mode;
    }

    public String getType() {
        return type;
    }

    public String getSrc() {
        return src;
    }

    public String getInlineContent() {
        return inlineContent;
    }

    public String getRoot() {
        return root;
    }

    public String getXmlLang() {
        return xmlLang;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "VxmlGrammar{mode='" + mode + "', type='" + type + "', src='" + src + "', inlineContent='" + inlineContent + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlGrammar that)) return false;
        return Objects.equals(mode, that.mode) && Objects.equals(type, that.type) && Objects.equals(src, that.src) && Objects.equals(inlineContent, that.inlineContent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, type, src, inlineContent);
    }
}
