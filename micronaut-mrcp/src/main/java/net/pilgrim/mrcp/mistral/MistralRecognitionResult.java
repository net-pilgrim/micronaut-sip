package net.pilgrim.mrcp.mistral;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Result of a Mistral speech recognition operation.
 */
public class MistralRecognitionResult {

    private final String text;
    private final float confidence;
    private final String language;
    private final List<String> nBest;

    public MistralRecognitionResult(String text, float confidence, String language, List<String> nBest) {
        this.text = text != null ? text.trim() : "";
        this.confidence = Math.max(0.0f, Math.min(1.0f, confidence));
        this.language = language != null ? language : "en-US";
        this.nBest = (nBest != null) ? new ArrayList<>(nBest) : Collections.emptyList();
    }

    public MistralRecognitionResult(String text, float confidence) {
        this(text, confidence, "en-US", Collections.emptyList());
    }

    public String getText() {
        return text;
    }

    public float getConfidence() {
        return confidence;
    }

    public String getLanguage() {
        return language;
    }

    public List<String> getNBest() {
        return Collections.unmodifiableList(nBest);
    }

    /**
     * Formats this result as W3C Natural Language Semantics Markup Language (NLSML)
     * as required by RFC 6787 for MRCP RECOGNITION-COMPLETE events.
     */
    public String toNlsml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n");
        sb.append("<result xmlns=\"http://www.ietf.org/xml/ns/mrcpv2\">\r\n");
        sb.append("  <interpretation grammar=\"session:default\" confidence=\"")
          .append(String.format(Locale.ROOT, "%.2f", confidence))
          .append("\">\r\n");
        sb.append("    <instance>").append(escapeXml(text)).append("</instance>\r\n");
        sb.append("    <input mode=\"speech\">").append(escapeXml(text)).append("</input>\r\n");
        sb.append("  </interpretation>\r\n");

        for (String alt : nBest) {
            if (!alt.equalsIgnoreCase(text)) {
                sb.append("  <interpretation grammar=\"session:default\" confidence=\"")
                  .append(String.format(Locale.ROOT, "%.2f", Math.max(0.0f, confidence - 0.1f)))
                  .append("\">\r\n");
                sb.append("    <instance>").append(escapeXml(alt)).append("</instance>\r\n");
                sb.append("    <input mode=\"speech\">").append(escapeXml(alt)).append("</input>\r\n");
                sb.append("  </interpretation>\r\n");
            }
        }

        sb.append("</result>\r\n");
        return sb.toString();
    }

    private static String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }

    @Override
    public String toString() {
        return "MistralRecognitionResult{text='" + text + "', confidence=" + confidence + "}";
    }
}
