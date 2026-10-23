package net.pilgrim.vxml.grammar.srgs;

import java.io.Serializable;
import java.util.*;

/**
 * Root representation of a W3C Speech Recognition Grammar Specification (SRGS) grammar.
 */
public class SrgsGrammar implements Serializable {

    private final String mode;       // "voice" or "dtmf"
    private final String rootRuleId;
    private final String xmlLang;
    private final String version;
    private final String tagFormat;
    private final Map<String, SrgsRule> rules;

    public SrgsGrammar(String mode,
                       String rootRuleId,
                       String xmlLang,
                       String version,
                       String tagFormat,
                       Map<String, SrgsRule> rules) {
        this.mode = mode != null ? mode.trim().toLowerCase(Locale.ROOT) : "voice";
        this.rootRuleId = rootRuleId != null ? rootRuleId.trim() : null;
        this.xmlLang = xmlLang != null ? xmlLang.trim() : "en-US";
        this.version = version != null ? version.trim() : "1.0";
        this.tagFormat = tagFormat != null ? tagFormat.trim() : null;
        this.rules = rules != null ? new LinkedHashMap<>(rules) : new LinkedHashMap<>();
    }

    public String getMode() {
        return mode;
    }

    public boolean isDtmfMode() {
        return "dtmf".equalsIgnoreCase(mode);
    }

    public boolean isVoiceMode() {
        return "voice".equalsIgnoreCase(mode);
    }

    public String getRootRuleId() {
        return rootRuleId;
    }

    public String getXmlLang() {
        return xmlLang;
    }

    public String getVersion() {
        return version;
    }

    public String getTagFormat() {
        return tagFormat;
    }

    public Map<String, SrgsRule> getRules() {
        return Collections.unmodifiableMap(rules);
    }

    public SrgsRule getRule(String id) {
        if (id == null) return null;
        return rules.get(id.startsWith("#") ? id.substring(1) : id);
    }

    /**
     * Resolves the entry root rule.
     * If rootRuleId is specified, returns that rule.
     * Otherwise, returns the first public rule, or simply the first defined rule.
     */
    public SrgsRule getRootRule() {
        if (rootRuleId != null && rules.containsKey(rootRuleId)) {
            return rules.get(rootRuleId);
        }
        for (SrgsRule rule : rules.values()) {
            if (rule.isPublic()) {
                return rule;
            }
        }
        if (!rules.isEmpty()) {
            return rules.values().iterator().next();
        }
        return null;
    }

    @Override
    public String toString() {
        return "SrgsGrammar{mode='" + mode + "', root='" + rootRuleId + "', rules=" + rules.size() + '}';
    }
}
