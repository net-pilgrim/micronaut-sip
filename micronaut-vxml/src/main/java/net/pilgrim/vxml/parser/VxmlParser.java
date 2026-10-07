package net.pilgrim.vxml.parser;

import net.pilgrim.vxml.ast.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Secure XML parser that transforms VoiceXML 2.1 documents into strongly typed AST nodes.
 * Hardened against XML External Entity (XXE) and external DTD attacks.
 */
public class VxmlParser {

    private final DocumentBuilderFactory factory;

    public VxmlParser() {
        this.factory = DocumentBuilderFactory.newInstance();
        this.factory.setNamespaceAware(true);
        try {
            this.factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            this.factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            this.factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            this.factory.setXIncludeAware(false);
            this.factory.setExpandEntityReferences(false);
        } catch (Exception ignored) {
            // Some parsers may not support all hardening flags
        }
    }

    public VxmlDocument parse(String xmlContent) throws Exception {
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new IllegalArgumentException("VoiceXML content cannot be empty");
        }

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xmlContent)));
        Element root = doc.getDocumentElement();

        if (!"vxml".equalsIgnoreCase(root.getLocalName()) && !"vxml".equalsIgnoreCase(root.getNodeName())) {
            throw new IllegalArgumentException("Root element must be <vxml>, found: <" + root.getNodeName() + ">");
        }

        String version = getAttribute(root, "version", "2.1");
        String xmlns = getAttribute(root, "xmlns", null);

        List<VxmlVar> documentVars = new ArrayList<>();
        List<VxmlForm> forms = new ArrayList<>();

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) node;
            String tagName = getLocalTagName(el);

            switch (tagName.toLowerCase()) {
                case "var" -> documentVars.add(parseVar(el));
                case "form" -> forms.add(parseForm(el));
                case "menu" -> {
                    VxmlMenu menu = parseMenu(el);
                    forms.add(menu.toForm());
                }
            }
        }

        return new VxmlDocument(version, xmlns, documentVars, forms);
    }

    private VxmlForm parseForm(Element formEl) {
        String id = getAttribute(formEl, "id", null);
        List<VxmlFormItem> items = new ArrayList<>();

        NodeList children = formEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) node;
            String tagName = getLocalTagName(el);

            switch (tagName.toLowerCase()) {
                case "var" -> items.add(parseVar(el));
                case "block" -> items.add(parseBlock(el));
                case "field" -> items.add(parseField(el));
                case "record" -> items.add(parseRecord(el));
            }
        }

        return new VxmlForm(id, items);
    }

    private VxmlMenu parseMenu(Element menuEl) {
        String id = getAttribute(menuEl, "id", null);
        VxmlPrompt prompt = null;
        List<VxmlChoice> choices = new ArrayList<>();

        NodeList children = menuEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) node;
            String tagName = getLocalTagName(el);

            if ("prompt".equalsIgnoreCase(tagName)) {
                prompt = parsePrompt(el);
            } else if ("choice".equalsIgnoreCase(tagName)) {
                choices.add(parseChoice(el));
            }
        }

        return new VxmlMenu(id, prompt, choices);
    }

    private VxmlBlock parseBlock(Element blockEl) {
        String name = getAttribute(blockEl, "name", null);
        String cond = getAttribute(blockEl, "cond", null);
        List<VxmlExecutable> executables = parseExecutables(blockEl);
        return new VxmlBlock(name, cond, executables);
    }

    private VxmlField parseField(Element fieldEl) {
        String name = getAttribute(fieldEl, "name", "field_" + System.nanoTime());
        String cond = getAttribute(fieldEl, "cond", null);
        String expr = getAttribute(fieldEl, "expr", null);
        String type = getAttribute(fieldEl, "type", null);
        String slot = getAttribute(fieldEl, "slot", null);

        List<VxmlPrompt> prompts = new ArrayList<>();
        List<VxmlGrammar> grammars = new ArrayList<>();
        List<VxmlChoice> choices = new ArrayList<>();
        VxmlFilled filled = null;
        VxmlNoInput noInput = null;
        VxmlNoMatch noMatch = null;

        NodeList children = fieldEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) node;
            String tagName = getLocalTagName(el);

            switch (tagName.toLowerCase()) {
                case "prompt" -> prompts.add(parsePrompt(el));
                case "grammar" -> grammars.add(parseGrammar(el));
                case "choice" -> choices.add(parseChoice(el));
                case "filled" -> filled = parseFilled(el);
                case "noinput" -> noInput = parseNoInput(el);
                case "nomatch" -> noMatch = parseNoMatch(el);
            }
        }

        return new VxmlField(name, cond, expr, type, slot, prompts, grammars, choices, filled, noInput, noMatch);
    }

    private VxmlRecord parseRecord(Element recordEl) {
        String name = getAttribute(recordEl, "name", "recording");
        String cond = getAttribute(recordEl, "cond", null);
        String expr = getAttribute(recordEl, "expr", null);
        boolean beep = "true".equalsIgnoreCase(getAttribute(recordEl, "beep", "false"));
        long maxtimeMs = VxmlRecord.parseDuration(getAttribute(recordEl, "maxtime", "30s"), 30_000L);
        long finalsilenceMs = VxmlRecord.parseDuration(getAttribute(recordEl, "finalsilence", "3s"), 3_000L);
        boolean dtmfterm = !"false".equalsIgnoreCase(getAttribute(recordEl, "dtmfterm", "true"));
        String type = getAttribute(recordEl, "type", "audio/x-wav");

        List<VxmlPrompt> prompts = new ArrayList<>();
        VxmlFilled filled = null;
        VxmlNoInput noInput = null;

        NodeList children = recordEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) node;
            String tagName = getLocalTagName(el);

            if ("prompt".equalsIgnoreCase(tagName)) {
                prompts.add(parsePrompt(el));
            } else if ("filled".equalsIgnoreCase(tagName)) {
                filled = parseFilled(el);
            } else if ("noinput".equalsIgnoreCase(tagName)) {
                noInput = parseNoInput(el);
            }
        }

        return new VxmlRecord(name, cond, expr, beep, maxtimeMs, finalsilenceMs, dtmfterm, type, prompts, filled, noInput);
    }

    private List<VxmlExecutable> parseExecutables(Element containerEl) {
        List<VxmlExecutable> executables = new ArrayList<>();
        NodeList children = containerEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            VxmlExecutable exec = parseExecutable((Element) node);
            if (exec != null) {
                executables.add(exec);
            }
        }
        return executables;
    }

    private VxmlExecutable parseExecutable(Element el) {
        String tagName = getLocalTagName(el);
        return switch (tagName.toLowerCase()) {
            case "prompt" -> parsePrompt(el);
            case "audio" -> parseAudio(el);
            case "value" -> parseValue(el);
            case "assign" -> parseAssign(el);
            case "var" -> parseVar(el);
            case "goto" -> parseGoto(el);
            case "if" -> parseIf(el);
            case "exit" -> parseExit(el);
            case "disconnect" -> new VxmlDisconnect();
            case "clear" -> parseClear(el);
            case "reprompt" -> new VxmlReprompt();
            default -> null;
        };
    }

    private VxmlPrompt parsePrompt(Element promptEl) {
        boolean bargeIn = getBooleanAttribute(promptEl, "bargein", true);
        long timeoutMs = parseTimeout(getAttribute(promptEl, "timeout", "5000"));
        String cond = getAttribute(promptEl, "cond", null);
        int count = getIntAttribute(promptEl, "count", 1);

        List<VxmlPromptContent> contents = new ArrayList<>();
        NodeList children = promptEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.TEXT_NODE) {
                String text = node.getTextContent();
                if (text != null && !text.isBlank()) {
                    contents.add(new VxmlTextContent(text));
                }
            } else if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) node;
                String tagName = getLocalTagName(el);
                if ("audio".equalsIgnoreCase(tagName)) {
                    contents.add(parseAudio(el));
                } else if ("value".equalsIgnoreCase(tagName)) {
                    contents.add(parseValue(el));
                }
            }
        }

        return new VxmlPrompt(bargeIn, timeoutMs, cond, count, contents);
    }

    private VxmlAudio parseAudio(Element audioEl) {
        String src = getAttribute(audioEl, "src", null);
        String expr = getAttribute(audioEl, "expr", null);
        String alt = getAttribute(audioEl, "alt", null);
        return new VxmlAudio(src, expr, alt);
    }

    private VxmlValue parseValue(Element valEl) {
        String expr = getAttribute(valEl, "expr", "");
        return new VxmlValue(expr);
    }

    private VxmlChoice parseChoice(Element choiceEl) {
        String dtmf = getAttribute(choiceEl, "dtmf", null);
        String next = getAttribute(choiceEl, "next", null);
        String expr = getAttribute(choiceEl, "expr", null);
        String event = getAttribute(choiceEl, "event", null);
        String text = choiceEl.getTextContent() != null ? choiceEl.getTextContent().trim() : "";
        return new VxmlChoice(dtmf, next, expr, event, text);
    }

    private VxmlGoto parseGoto(Element gotoEl) {
        String next = getAttribute(gotoEl, "next", null);
        String expr = getAttribute(gotoEl, "expr", null);
        String nextItem = getAttribute(gotoEl, "nextitem", null);
        return new VxmlGoto(next, expr, nextItem);
    }

    private VxmlAssign parseAssign(Element assignEl) {
        String name = getAttribute(assignEl, "name", "");
        String expr = getAttribute(assignEl, "expr", null);
        return new VxmlAssign(name, expr);
    }

    private VxmlVar parseVar(Element varEl) {
        String name = getAttribute(varEl, "name", "");
        String expr = getAttribute(varEl, "expr", null);
        return new VxmlVar(name, expr);
    }

    private VxmlIf parseIf(Element ifEl) {
        String cond = getAttribute(ifEl, "cond", "true");
        List<VxmlExecutable> thenExecutables = new ArrayList<>();
        List<VxmlElseIf> elseIfs = new ArrayList<>();
        List<VxmlExecutable> elseExecutables = new ArrayList<>();

        int state = 0; // 0: then, 1: elseif, 2: else
        VxmlElseIf currentElseIf = null;

        NodeList children = ifEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) node;
            String tagName = getLocalTagName(el);

            if ("elseif".equalsIgnoreCase(tagName)) {
                state = 1;
                String elseIfCond = getAttribute(el, "cond", "true");
                currentElseIf = new VxmlElseIf(elseIfCond, new ArrayList<>());
                elseIfs.add(currentElseIf);
            } else if ("else".equalsIgnoreCase(tagName)) {
                state = 2;
                currentElseIf = null;
            } else {
                VxmlExecutable exec = parseExecutable(el);
                if (exec != null) {
                    if (state == 0) {
                        thenExecutables.add(exec);
                    } else if (state == 1 && currentElseIf != null) {
                        currentElseIf.getExecutables();
                        // Add to current elseIf (which has internal list)
                        try {
                            java.lang.reflect.Field f = VxmlElseIf.class.getDeclaredField("executables");
                            f.setAccessible(true);
                            @SuppressWarnings("unchecked")
                            List<VxmlExecutable> list = (List<VxmlExecutable>) f.get(currentElseIf);
                            list.add(exec);
                        } catch (Exception ignored) {}
                    } else if (state == 2) {
                        elseExecutables.add(exec);
                    }
                }
            }
        }

        return new VxmlIf(cond, thenExecutables, elseIfs, elseExecutables);
    }

    private VxmlFilled parseFilled(Element filledEl) {
        String mode = getAttribute(filledEl, "mode", "all");
        String namelistStr = getAttribute(filledEl, "namelist", null);
        List<String> namelist = namelistStr != null ? Arrays.asList(namelistStr.trim().split("\\s+")) : List.of();
        List<VxmlExecutable> executables = parseExecutables(filledEl);
        return new VxmlFilled(mode, namelist, executables);
    }

    private VxmlNoInput parseNoInput(Element noInputEl) {
        int count = getIntAttribute(noInputEl, "count", 1);
        List<VxmlExecutable> executables = parseExecutables(noInputEl);
        return new VxmlNoInput(count, executables);
    }

    private VxmlNoMatch parseNoMatch(Element noMatchEl) {
        int count = getIntAttribute(noMatchEl, "count", 1);
        List<VxmlExecutable> executables = parseExecutables(noMatchEl);
        return new VxmlNoMatch(count, executables);
    }

    private VxmlExit parseExit(Element exitEl) {
        String expr = getAttribute(exitEl, "expr", null);
        String namelist = getAttribute(exitEl, "namelist", null);
        return new VxmlExit(expr, namelist);
    }

    private VxmlClear parseClear(Element clearEl) {
        String namelistStr = getAttribute(clearEl, "namelist", null);
        List<String> namelist = namelistStr != null ? Arrays.asList(namelistStr.trim().split("\\s+")) : List.of();
        return new VxmlClear(namelist);
    }

    private VxmlGrammar parseGrammar(Element grammarEl) {
        String mode = getAttribute(grammarEl, "mode", "dtmf");
        String type = getAttribute(grammarEl, "type", null);
        String src = getAttribute(grammarEl, "src", null);
        String inline = grammarEl.getTextContent() != null ? grammarEl.getTextContent().trim() : null;
        return new VxmlGrammar(mode, type, src, inline);
    }

    private String getLocalTagName(Element el) {
        String name = el.getLocalName();
        return name != null ? name : el.getNodeName();
    }

    private String getAttribute(Element el, String name, String defaultValue) {
        if (el.hasAttribute(name)) {
            String val = el.getAttribute(name);
            return val != null ? val.trim() : defaultValue;
        }
        return defaultValue;
    }

    private boolean getBooleanAttribute(Element el, String name, boolean defaultValue) {
        if (el.hasAttribute(name)) {
            return Boolean.parseBoolean(el.getAttribute(name).trim());
        }
        return defaultValue;
    }

    private int getIntAttribute(Element el, String name, int defaultValue) {
        if (el.hasAttribute(name)) {
            try {
                return Integer.parseInt(el.getAttribute(name).trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private long parseTimeout(String timeoutStr) {
        if (timeoutStr == null || timeoutStr.isBlank()) {
            return 5000L;
        }
        String clean = timeoutStr.trim().toLowerCase();
        try {
            if (clean.endsWith("ms")) {
                return Long.parseLong(clean.substring(0, clean.length() - 2).trim());
            } else if (clean.endsWith("s")) {
                double secs = Double.parseDouble(clean.substring(0, clean.length() - 1).trim());
                return (long) (secs * 1000);
            } else {
                return Long.parseLong(clean);
            }
        } catch (NumberFormatException ignored) {
            return 5000L;
        }
    }
}
