package net.pilgrim.ccxml.parser;

import net.pilgrim.ccxml.ast.*;
import net.pilgrim.ccxml.bridge.DuplexMode;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;

/**
 * Hardened XML parser for W3C CCXML 1.0 documents.
 */
public class CcxmlParser {

    private final DocumentBuilderFactory factory;

    public CcxmlParser() {
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

    public CcxmlDocument parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new CcxmlParseException("CCXML content cannot be null or empty");
        }
        return parse(new InputSource(new StringReader(xml)));
    }

    public CcxmlDocument parse(InputStream in) {
        if (in == null) {
            throw new CcxmlParseException("InputStream cannot be null");
        }
        return parse(new InputSource(in));
    }

    public CcxmlDocument parse(Reader reader) {
        if (reader == null) {
            throw new CcxmlParseException("Reader cannot be null");
        }
        return parse(new InputSource(reader));
    }

    public CcxmlDocument parse(InputSource is) {
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);
            Element root = doc.getDocumentElement();
            if (!"ccxml".equalsIgnoreCase(getLocalName(root))) {
                throw new CcxmlParseException("Root element must be <ccxml>, got: <" + root.getTagName() + ">");
            }
            return parseCcxml(root);
        } catch (CcxmlParseException e) {
            throw e;
        } catch (Exception e) {
            throw new CcxmlParseException("Failed to parse CCXML document: " + e.getMessage(), e);
        }
    }

    private CcxmlDocument parseCcxml(Element root) {
        String version = getAttr(root, "version", "1.0");

        List<CcxmlVar> vars = new ArrayList<>();
        List<CcxmlScript> scripts = new ArrayList<>();
        CcxmlEventProcessor eventProcessor = null;

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) node;
            String tag = getLocalName(el).toLowerCase();

            switch (tag) {
                case "var" -> vars.add(parseVar(el));
                case "script" -> scripts.add(parseScript(el));
                case "eventprocessor" -> {
                    if (eventProcessor != null) {
                        throw new CcxmlParseException("Only one <eventprocessor> is allowed per CCXML document");
                    }
                    eventProcessor = parseEventProcessor(el);
                }
            }
        }

        if (eventProcessor == null) {
            eventProcessor = new CcxmlEventProcessor("current_state", Collections.emptyList());
        }

        return new CcxmlDocument(version, vars, scripts, eventProcessor);
    }

    private CcxmlEventProcessor parseEventProcessor(Element el) {
        String stateVariable = getAttr(el, "statevariable", "current_state");
        List<CcxmlTransition> transitions = new ArrayList<>();

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element childEl = (Element) node;
            if ("transition".equalsIgnoreCase(getLocalName(childEl))) {
                transitions.add(parseTransition(childEl));
            }
        }

        return new CcxmlEventProcessor(stateVariable, transitions);
    }

    private CcxmlTransition parseTransition(Element el) {
        String event = getAttr(el, "event", null);
        String state = getAttr(el, "state", null);
        String cond = getAttr(el, "cond", null);
        List<CcxmlExecutable> executables = parseExecutables(el);
        return new CcxmlTransition(event, state, cond, executables);
    }

    private List<CcxmlExecutable> parseExecutables(Element parent) {
        List<CcxmlExecutable> executables = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) node;
            String tag = getLocalName(el).toLowerCase();

            switch (tag) {
                case "var" -> executables.add(parseVar(el));
                case "assign" -> executables.add(parseAssign(el));
                case "script" -> executables.add(parseScript(el));
                case "send" -> executables.add(parseSend(el));
                case "cancel" -> executables.add(parseCancel(el));
                case "exit" -> executables.add(parseExit(el));
                case "if" -> executables.add(parseIf(el));
                case "accept" -> executables.add(parseAccept(el));
                case "reject" -> executables.add(parseReject(el));
                case "createcall" -> executables.add(parseCreateCall(el));
                case "redirect" -> executables.add(parseRedirect(el));
                case "disconnect" -> executables.add(parseDisconnect(el));
                case "dialogstart" -> executables.add(parseDialogStart(el));
                case "dialogterminate" -> executables.add(parseDialogTerminate(el));
                case "dialogprepare" -> executables.add(parseDialogPrepare(el));
                case "createconference" -> executables.add(parseCreateConference(el));
                case "destroyconference" -> executables.add(parseDestroyConference(el));
                case "join" -> executables.add(parseJoin(el));
                case "unjoin" -> executables.add(parseUnjoin(el));
            }
        }
        return executables;
    }

    private CcxmlVar parseVar(Element el) {
        String name = getAttr(el, "name", null);
        if (name == null || name.isBlank()) {
            throw new CcxmlParseException("<var> element must have a 'name' attribute");
        }
        String expr = getAttr(el, "expr", null);
        return new CcxmlVar(name, expr);
    }

    private CcxmlAssign parseAssign(Element el) {
        String name = getAttr(el, "name", null);
        if (name == null || name.isBlank()) {
            throw new CcxmlParseException("<assign> element must have a 'name' attribute");
        }
        String expr = getAttr(el, "expr", null);
        return new CcxmlAssign(name, expr);
    }

    private CcxmlScript parseScript(Element el) {
        String src = getAttr(el, "src", null);
        String text = el.getTextContent();
        return new CcxmlScript(text != null ? text.trim() : null, src);
    }

    private CcxmlSend parseSend(Element el) {
        String event = getAttr(el, "event", null);
        String target = getAttr(el, "target", null);
        String targetType = getAttr(el, "targettype", null);
        String sendId = getAttr(el, "sendid", null);
        String delay = getAttr(el, "delay", null);
        String namelist = getAttr(el, "namelist", null);
        return new CcxmlSend(event, target, targetType, sendId, delay, namelist);
    }

    private CcxmlCancel parseCancel(Element el) {
        String sendId = getAttr(el, "sendid", null);
        return new CcxmlCancel(sendId);
    }

    private CcxmlExit parseExit(Element el) {
        String expr = getAttr(el, "expr", null);
        String namelist = getAttr(el, "namelist", null);
        return new CcxmlExit(expr, namelist);
    }

    private record ElseIfHolder(String cond, List<CcxmlExecutable> executables) {}

    private CcxmlIf parseIf(Element el) {
        String cond = getAttr(el, "cond", null);
        List<CcxmlExecutable> thenExecs = new ArrayList<>();
        List<ElseIfHolder> elseIfHolders = new ArrayList<>();
        List<CcxmlExecutable> elseExecs = null;

        List<CcxmlExecutable> currentTarget = thenExecs;

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element childEl = (Element) node;
            String tag = getLocalName(childEl).toLowerCase();

            if ("elseif".equals(tag)) {
                String elseIfCond = getAttr(childEl, "cond", null);
                List<CcxmlExecutable> holderList = new ArrayList<>();
                elseIfHolders.add(new ElseIfHolder(elseIfCond, holderList));
                currentTarget = holderList;
            } else if ("else".equals(tag)) {
                elseExecs = new ArrayList<>();
                currentTarget = elseExecs;
            } else {
                List<CcxmlExecutable> single = parseSingleExecutable(childEl);
                if (single != null) {
                    currentTarget.addAll(single);
                }
            }
        }

        List<CcxmlElseIf> elseIfs = new ArrayList<>();
        for (ElseIfHolder h : elseIfHolders) {
            elseIfs.add(new CcxmlElseIf(h.cond, h.executables));
        }
        CcxmlElse elseBlock = elseExecs != null ? new CcxmlElse(elseExecs) : null;

        return new CcxmlIf(cond, thenExecs, elseIfs, elseBlock);
    }

    private List<CcxmlExecutable> parseSingleExecutable(Element el) {
        String tag = getLocalName(el).toLowerCase();
        return switch (tag) {
            case "var" -> List.of(parseVar(el));
            case "assign" -> List.of(parseAssign(el));
            case "script" -> List.of(parseScript(el));
            case "send" -> List.of(parseSend(el));
            case "cancel" -> List.of(parseCancel(el));
            case "exit" -> List.of(parseExit(el));
            case "if" -> List.of(parseIf(el));
            case "accept" -> List.of(parseAccept(el));
            case "reject" -> List.of(parseReject(el));
            case "createcall" -> List.of(parseCreateCall(el));
            case "redirect" -> List.of(parseRedirect(el));
            case "disconnect" -> List.of(parseDisconnect(el));
            case "dialogstart" -> List.of(parseDialogStart(el));
            case "dialogterminate" -> List.of(parseDialogTerminate(el));
            case "dialogprepare" -> List.of(parseDialogPrepare(el));
            case "createconference" -> List.of(parseCreateConference(el));
            case "destroyconference" -> List.of(parseDestroyConference(el));
            case "join" -> List.of(parseJoin(el));
            case "unjoin" -> List.of(parseUnjoin(el));
            default -> Collections.emptyList();
        };
    }

    private CcxmlAccept parseAccept(Element el) {
        return new CcxmlAccept(getAttr(el, "connectionid", null), getAttr(el, "hints", null));
    }

    private CcxmlReject parseReject(Element el) {
        String connId = getAttr(el, "connectionid", null);
        String reason = getAttr(el, "reason", "Busy Here");
        String statusStr = getAttr(el, "status", null);
        int status = 486;
        if (statusStr != null) {
            try {
                status = Integer.parseInt(statusStr);
            } catch (NumberFormatException ignored) {}
        }
        return new CcxmlReject(connId, reason, status, getAttr(el, "hints", null));
    }

    private CcxmlCreateCall parseCreateCall(Element el) {
        String dest = getAttr(el, "dest", null);
        String callerId = getAttr(el, "callerid", null);
        String connectionId = getAttr(el, "connectionid", null);
        String timeout = getAttr(el, "timeout", null);
        String aai = getAttr(el, "aai", null);
        return new CcxmlCreateCall(dest, callerId, connectionId, timeout, aai);
    }

    private CcxmlRedirect parseRedirect(Element el) {
        return new CcxmlRedirect(getAttr(el, "connectionid", null), getAttr(el, "dest", null), getAttr(el, "reason", null));
    }

    private CcxmlDisconnect parseDisconnect(Element el) {
        return new CcxmlDisconnect(getAttr(el, "connectionid", null), getAttr(el, "reason", null));
    }

    private CcxmlDialogStart parseDialogStart(Element el) {
        String src = getAttr(el, "src", null);
        String type = getAttr(el, "type", "application/voicexml+xml");
        String dialogId = getAttr(el, "dialogid", null);
        String connectionId = getAttr(el, "connectionid", null);
        String conferenceId = getAttr(el, "conferenceid", null);
        String namelist = getAttr(el, "namelist", null);

        Map<String, String> params = new LinkedHashMap<>();
        NodeList children = el.getElementsByTagName("param");
        for (int i = 0; i < children.getLength(); i++) {
            Element paramEl = (Element) children.item(i);
            String name = getAttr(paramEl, "name", null);
            String val = getAttr(paramEl, "expr", getAttr(paramEl, "value", null));
            if (name != null) {
                params.put(name, val);
            }
        }
        return new CcxmlDialogStart(src, type, dialogId, connectionId, conferenceId, namelist, params);
    }

    private CcxmlDialogTerminate parseDialogTerminate(Element el) {
        String dialogId = getAttr(el, "dialogid", null);
        boolean immediate = Boolean.parseBoolean(getAttr(el, "immediate", "false"));
        return new CcxmlDialogTerminate(dialogId, immediate);
    }

    private CcxmlDialogPrepare parseDialogPrepare(Element el) {
        return new CcxmlDialogPrepare(getAttr(el, "src", null), getAttr(el, "type", "application/voicexml+xml"), getAttr(el, "dialogid", null));
    }

    private CcxmlCreateConference parseCreateConference(Element el) {
        String confId = getAttr(el, "conferenceid", null);
        String confName = getAttr(el, "confname", null);
        String talkersStr = getAttr(el, "reservedtalkers", null);
        Integer talkers = null;
        if (talkersStr != null) {
            try {
                talkers = Integer.parseInt(talkersStr);
            } catch (NumberFormatException ignored) {}
        }
        return new CcxmlCreateConference(confId, confName, talkers);
    }

    private CcxmlDestroyConference parseDestroyConference(Element el) {
        return new CcxmlDestroyConference(getAttr(el, "conferenceid", null));
    }

    private CcxmlJoin parseJoin(Element el) {
        String id1 = getAttr(el, "id1", null);
        String id2 = getAttr(el, "id2", null);
        DuplexMode duplex = DuplexMode.fromString(getAttr(el, "duplex", "full"));
        return new CcxmlJoin(id1, id2, duplex);
    }

    private CcxmlUnjoin parseUnjoin(Element el) {
        return new CcxmlUnjoin(getAttr(el, "id1", null), getAttr(el, "id2", null));
    }

    private String getLocalName(Element el) {
        String name = el.getLocalName();
        return name != null ? name : el.getTagName();
    }

    private String getAttr(Element el, String name, String defaultVal) {
        if (el.hasAttribute(name)) {
            return el.getAttribute(name);
        }
        return defaultVal;
    }
}
