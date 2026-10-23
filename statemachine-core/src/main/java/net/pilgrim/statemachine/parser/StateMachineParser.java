package net.pilgrim.statemachine.parser;

import net.pilgrim.statemachine.action.*;
import net.pilgrim.statemachine.model.StateMachineDefinition;
import net.pilgrim.statemachine.model.Transition;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Hardened XML parser for domain-agnostic event-driven state machines with extensible action tag handlers.
 */
public class StateMachineParser {

    private final DocumentBuilderFactory factory;
    private final Map<String, Function<Element, Action>> customActionParsers = new ConcurrentHashMap<>();

    public StateMachineParser() {
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

    public void registerActionParser(String tagName, Function<Element, Action> parser) {
        if (tagName != null && parser != null) {
            customActionParsers.put(tagName.toLowerCase(), parser);
        }
    }

    public StateMachineDefinition parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new StateMachineParseException("XML content cannot be null or empty");
        }
        return parse(new InputSource(new StringReader(xml)));
    }

    public StateMachineDefinition parse(InputStream in) {
        if (in == null) {
            throw new StateMachineParseException("InputStream cannot be null");
        }
        return parse(new InputSource(in));
    }

    public StateMachineDefinition parse(Reader reader) {
        if (reader == null) {
            throw new StateMachineParseException("Reader cannot be null");
        }
        return parse(new InputSource(reader));
    }

    public StateMachineDefinition parse(InputSource is) {
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);
            Element root = doc.getDocumentElement();
            String rootTag = getLocalName(root).toLowerCase();
            if (!"statemachine".equals(rootTag) && !"scxml".equals(rootTag) && !"ccxml".equals(rootTag)) {
                throw new StateMachineParseException("Root element must be <statemachine>, <scxml>, or <ccxml>, got: <" + root.getTagName() + ">");
            }
            return parseDefinition(root);
        } catch (StateMachineParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StateMachineParseException("Failed to parse state machine document: " + e.getMessage(), e);
        }
    }

    public StateMachineDefinition parseDefinition(Element root) {
        String id = getAttr(root, "id", "statemachine");
        String initialState = getAttr(root, "initial", getAttr(root, "initialstate", "init"));
        String stateVariable = getAttr(root, "statevariable", "current_state");

        List<VarAction> variables = new ArrayList<>();
        List<Action> initActions = new ArrayList<>();
        List<Transition> transitions = new ArrayList<>();

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) node;
            String tag = getLocalName(el).toLowerCase();

            switch (tag) {
                case "var" -> variables.add(parseVar(el));
                case "script" -> initActions.add(parseScript(el));
                case "transition" -> transitions.add(parseTransition(el));
                case "eventprocessor" -> {
                    String epStateVar = getAttr(el, "statevariable", stateVariable);
                    stateVariable = epStateVar;
                    transitions.addAll(parseTransitionsFromProcessor(el));
                }
                case "state" -> {
                    String stateId = getAttr(el, "id", null);
                    transitions.addAll(parseTransitionsFromState(el, stateId));
                }
            }
        }

        return new StateMachineDefinition(id, initialState, stateVariable, variables, initActions, transitions);
    }

    private List<Transition> parseTransitionsFromProcessor(Element el) {
        List<Transition> transitions = new ArrayList<>();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element childEl = (Element) node;
            if ("transition".equalsIgnoreCase(getLocalName(childEl))) {
                transitions.add(parseTransition(childEl));
            }
        }
        return transitions;
    }

    private List<Transition> parseTransitionsFromState(Element stateEl, String stateId) {
        List<Transition> transitions = new ArrayList<>();
        NodeList children = stateEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element childEl = (Element) node;
            if ("transition".equalsIgnoreCase(getLocalName(childEl))) {
                Transition t = parseTransition(childEl);
                String effectiveState = t.state() != null && !t.state().isBlank() ? t.state() : stateId;
                transitions.add(new Transition(t.event(), effectiveState, t.target(), t.cond(), t.actions()));
            }
        }
        return transitions;
    }

    public Transition parseTransition(Element el) {
        String event = getAttr(el, "event", null);
        String state = getAttr(el, "state", null);
        String target = getAttr(el, "target", null);
        String cond = getAttr(el, "cond", null);
        List<Action> actions = parseActions(el);
        return new Transition(event, state, target, cond, actions);
    }

    public List<Action> parseActions(Element parent) {
        List<Action> actions = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) node;
            Action action = parseSingleAction(el);
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
    }

    public Action parseSingleAction(Element el) {
        String tag = getLocalName(el).toLowerCase();

        if (customActionParsers.containsKey(tag)) {
            return customActionParsers.get(tag).apply(el);
        }

        return switch (tag) {
            case "var" -> parseVar(el);
            case "assign" -> parseAssign(el);
            case "script" -> parseScript(el);
            case "send" -> parseSend(el);
            case "cancel" -> parseCancel(el);
            case "exit" -> parseExit(el);
            case "if" -> parseIf(el);
            default -> null;
        };
    }

    private VarAction parseVar(Element el) {
        String name = getAttr(el, "name", null);
        if (name == null || name.isBlank()) {
            throw new StateMachineParseException("<var> element must have a 'name' attribute");
        }
        return new VarAction(name, getAttr(el, "expr", null));
    }

    private AssignAction parseAssign(Element el) {
        String name = getAttr(el, "name", null);
        if (name == null || name.isBlank()) {
            throw new StateMachineParseException("<assign> element must have a 'name' attribute");
        }
        return new AssignAction(name, getAttr(el, "expr", null));
    }

    private ScriptAction parseScript(Element el) {
        String text = el.getTextContent();
        return new ScriptAction(text != null ? text.trim() : null);
    }

    private SendAction parseSend(Element el) {
        return new SendAction(
                getAttr(el, "event", null),
                getAttr(el, "target", null),
                getAttr(el, "targettype", null),
                getAttr(el, "sendid", null),
                getAttr(el, "delay", null),
                getAttr(el, "namelist", null)
        );
    }

    private CancelAction parseCancel(Element el) {
        return new CancelAction(getAttr(el, "sendid", null));
    }

    private ExitAction parseExit(Element el) {
        return new ExitAction(getAttr(el, "expr", null));
    }

    private IfAction parseIf(Element el) {
        String cond = getAttr(el, "cond", null);
        List<Action> thenActions = new ArrayList<>();
        List<ElseIfHolder> elseIfHolders = new ArrayList<>();
        List<Action> elseActions = null;

        List<Action> currentTarget = thenActions;

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element childEl = (Element) node;
            String tag = getLocalName(childEl).toLowerCase();

            if ("elseif".equals(tag)) {
                String elseIfCond = getAttr(childEl, "cond", null);
                List<Action> holderList = new ArrayList<>();
                elseIfHolders.add(new ElseIfHolder(elseIfCond, holderList));
                currentTarget = holderList;
            } else if ("else".equals(tag)) {
                elseActions = new ArrayList<>();
                currentTarget = elseActions;
            } else {
                Action single = parseSingleAction(childEl);
                if (single != null) {
                    currentTarget.add(single);
                }
            }
        }

        List<ElseIf> elseIfs = new ArrayList<>();
        for (ElseIfHolder h : elseIfHolders) {
            elseIfs.add(new ElseIf(h.cond, h.actions));
        }
        Else elseBlock = elseActions != null ? new Else(elseActions) : null;

        return new IfAction(cond, thenActions, elseIfs, elseBlock);
    }

    private record ElseIfHolder(String cond, List<Action> actions) {}

    public String getLocalName(Element el) {
        String name = el.getLocalName();
        return name != null ? name : el.getTagName();
    }

    public String getAttr(Element el, String name, String defaultVal) {
        if (el.hasAttribute(name)) {
            return el.getAttribute(name);
        }
        return defaultVal;
    }
}
