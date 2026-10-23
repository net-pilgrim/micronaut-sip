package net.pilgrim.vxml.grammar.srgs;

import java.util.Objects;

/**
 * References another grammar rule (&lt;ruleref uri="#ruleId"/&gt; or $ruleName).
 */
public class SrgsRuleRef implements SrgsNode {

    private final String uri;
    private final String ruleId;
    private final String special; // e.g. "GARBAGE", "NULL", "VOID"

    public SrgsRuleRef(String uri, String special) {
        this.uri = uri;
        this.special = special != null ? special.trim().toUpperCase(java.util.Locale.ROOT) : null;
        if (uri != null) {
            String cleanUri = uri.trim();
            if (cleanUri.startsWith("#")) {
                this.ruleId = cleanUri.substring(1);
            } else {
                this.ruleId = cleanUri;
            }
        } else {
            this.ruleId = null;
        }
    }

    public static SrgsRuleRef toRule(String ruleId) {
        return new SrgsRuleRef("#" + ruleId, null);
    }

    public static SrgsRuleRef garbage() {
        return new SrgsRuleRef(null, "GARBAGE");
    }

    public String getUri() {
        return uri;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getSpecial() {
        return special;
    }

    public boolean isGarbage() {
        return "GARBAGE".equalsIgnoreCase(special);
    }

    public boolean isNull() {
        return "NULL".equalsIgnoreCase(special);
    }

    public boolean isVoid() {
        return "VOID".equalsIgnoreCase(special);
    }

    @Override
    public String toString() {
        if (special != null) {
            return "SrgsRuleRef{special='" + special + "'}";
        }
        return "SrgsRuleRef{ruleId='" + ruleId + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SrgsRuleRef that)) return false;
        return Objects.equals(uri, that.uri) && Objects.equals(special, that.special);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, special);
    }
}
