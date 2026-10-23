package net.pilgrim.vxml.grammar.srgs;

import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

/**
 * Parses W3C Speech Recognition Grammar Specification (SRGS) 1.0 XML documents (GrXML).
 */
public class SrgsXmlParser {

    private final DocumentBuilderFactory factory;

    public SrgsXmlParser() {
        this.factory = DocumentBuilderFactory.newInstance();
        this.factory.setNamespaceAware(true);
        try {
            this.factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            this.factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            this.factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            this.factory.setXIncludeAware(false);
            this.factory.setExpandEntityReferences(false);
        } catch (Exception ignored) {
        }
    }

    public SrgsGrammar parse(String xmlContent) throws Exception {
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new IllegalArgumentException("SRGS grammar XML cannot be empty");
        }

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xmlContent.trim())));
        Element root = doc.getDocumentElement();
        String tagName = getLocalName(root);

        if ("grammar".equalsIgnoreCase(tagName)) {
            return parseGrammarElement(root);
        } else if ("rule".equalsIgnoreCase(tagName)) {
            SrgsRule singleRule = parseRule(root);
            Map<String, SrgsRule> rules = new LinkedHashMap<>();
            rules.put(singleRule.getId(), singleRule);
            return new SrgsGrammar("voice", singleRule.getId(), "en-US", "1.0", null, rules);
        } else {
            // Treat as anonymous body of a default rule
            SrgsNode bodyNode = parseContainerChildren(root);
            SrgsRule defaultRule = new SrgsRule("root", true, bodyNode);
            Map<String, SrgsRule> rules = new LinkedHashMap<>();
            rules.put("root", defaultRule);
            return new SrgsGrammar("voice", "root", "en-US", "1.0", null, rules);
        }
    }

    private SrgsGrammar parseGrammarElement(Element grammarEl) {
        String mode = getAttribute(grammarEl, "mode", "voice");
        String rootRuleId = getAttribute(grammarEl, "root", null);
        String xmlLang = getAttribute(grammarEl, "xml:lang", getAttribute(grammarEl, "lang", "en-US"));
        String version = getAttribute(grammarEl, "version", "1.0");
        String tagFormat = getAttribute(grammarEl, "tag-format", null);

        Map<String, SrgsRule> rules = new LinkedHashMap<>();
        List<SrgsNode> directNodes = new ArrayList<>();

        NodeList children = grammarEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) child;
                String name = getLocalName(el);
                if ("rule".equalsIgnoreCase(name)) {
                    SrgsRule rule = parseRule(el);
                    rules.put(rule.getId(), rule);
                } else {
                    SrgsNode node = parseElement(el);
                    if (node != null) directNodes.add(node);
                }
            }
        }

        // If rules are empty but direct children (e.g. <one-of>) were found
        if (rules.isEmpty() && !directNodes.isEmpty()) {
            SrgsNode rootNode = directNodes.size() == 1 ? directNodes.get(0) : new SrgsSequence(directNodes);
            SrgsRule autoRule = new SrgsRule(rootRuleId != null ? rootRuleId : "default", true, rootNode);
            rules.put(autoRule.getId(), autoRule);
            if (rootRuleId == null) rootRuleId = autoRule.getId();
        }

        return new SrgsGrammar(mode, rootRuleId, xmlLang, version, tagFormat, rules);
    }

    private SrgsRule parseRule(Element ruleEl) {
        String id = getAttribute(ruleEl, "id", "rule_" + System.nanoTime());
        String scope = getAttribute(ruleEl, "scope", "private");
        boolean isPublic = "public".equalsIgnoreCase(scope);

        SrgsNode contentNode = parseContainerChildren(ruleEl);
        return new SrgsRule(id, isPublic, contentNode);
    }

    private SrgsNode parseContainerChildren(Element container) {
        List<SrgsNode> nodes = new ArrayList<>();
        NodeList children = container.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                SrgsNode node = parseElement((Element) child);
                if (node != null) nodes.add(node);
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String text = child.getTextContent();
                if (text != null && !text.isBlank()) {
                    for (String token : text.trim().split("\\s+")) {
                        if (!token.isBlank()) {
                            nodes.add(new SrgsToken(token));
                        }
                    }
                }
            }
        }

        if (nodes.isEmpty()) {
            return null;
        }
        if (nodes.size() == 1) {
            return nodes.get(0);
        }
        return new SrgsSequence(nodes);
    }

    private SrgsNode parseElement(Element el) {
        String name = getLocalName(el);
        switch (name.toLowerCase(Locale.ROOT)) {
            case "one-of" -> {
                List<SrgsNode> items = new ArrayList<>();
                NodeList childNodes = el.getChildNodes();
                for (int i = 0; i < childNodes.getLength(); i++) {
                    Node c = childNodes.item(i);
                    if (c.getNodeType() == Node.ELEMENT_NODE) {
                        SrgsNode node = parseElement((Element) c);
                        if (node != null) items.add(node);
                    }
                }
                return new SrgsOneOf(items);
            }
            case "item" -> {
                String repeatAttr = getAttribute(el, "repeat", "1");
                int minRepeat = 1;
                int maxRepeat = 1;

                if (repeatAttr != null) {
                    if (repeatAttr.contains("-")) {
                        String[] parts = repeatAttr.split("-", 2);
                        try {
                            minRepeat = Integer.parseInt(parts[0].trim());
                        } catch (NumberFormatException ignored) {}
                        if ("*".equals(parts[1].trim())) {
                            maxRepeat = 999;
                        } else {
                            try {
                                maxRepeat = Integer.parseInt(parts[1].trim());
                            } catch (NumberFormatException ignored) {}
                        }
                    } else {
                        try {
                            minRepeat = Integer.parseInt(repeatAttr.trim());
                            maxRepeat = minRepeat;
                        } catch (NumberFormatException ignored) {}
                    }
                }

                float weight = 1.0f;
                String weightAttr = getAttribute(el, "weight", null);
                if (weightAttr != null) {
                    try {
                        weight = Float.parseFloat(weightAttr.trim());
                    } catch (NumberFormatException ignored) {}
                }

                // Check for embedded <tag> within the item
                String itemTag = null;
                List<SrgsNode> itemNodes = new ArrayList<>();
                NodeList childNodes = el.getChildNodes();

                for (int i = 0; i < childNodes.getLength(); i++) {
                    Node c = childNodes.item(i);
                    if (c.getNodeType() == Node.ELEMENT_NODE) {
                        Element childEl = (Element) c;
                        if ("tag".equalsIgnoreCase(getLocalName(childEl))) {
                            itemTag = childEl.getTextContent() != null ? childEl.getTextContent().trim() : "";
                        } else {
                            SrgsNode node = parseElement(childEl);
                            if (node != null) itemNodes.add(node);
                        }
                    } else if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                        String text = c.getTextContent();
                        if (text != null && !text.isBlank()) {
                            for (String token : text.trim().split("\\s+")) {
                                if (!token.isBlank()) {
                                    itemNodes.add(new SrgsToken(token));
                                }
                            }
                        }
                    }
                }

                SrgsNode childNode = itemNodes.isEmpty() ? null :
                        (itemNodes.size() == 1 ? itemNodes.get(0) : new SrgsSequence(itemNodes));

                return new SrgsItem(childNode, minRepeat, maxRepeat, weight, itemTag);
            }
            case "ruleref" -> {
                String uri = getAttribute(el, "uri", null);
                String special = getAttribute(el, "special", null);
                return new SrgsRuleRef(uri, special);
            }
            case "token" -> {
                String text = el.getTextContent();
                return new SrgsToken(text != null ? text.trim() : "");
            }
            case "tag" -> {
                String text = el.getTextContent();
                return new SrgsTag(text != null ? text.trim() : "");
            }
            default -> {
                return parseContainerChildren(el);
            }
        }
    }

    private String getLocalName(Element el) {
        String local = el.getLocalName();
        return local != null ? local : el.getNodeName();
    }

    private String getAttribute(Element el, String name, String defaultValue) {
        if (el.hasAttribute(name)) {
            String val = el.getAttribute(name);
            return val != null ? val.trim() : defaultValue;
        }
        return defaultValue;
    }
}
