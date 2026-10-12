package net.pilgrim.msml.parser;

import net.pilgrim.msml.model.*;
import net.pilgrim.msml.model.moml.*;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * High-performance, secure XML parser for RFC 5707 Media Server Markup Language (MSML).
 */
public class MsmlParser {

    private final DocumentBuilderFactory factory;

    public MsmlParser() {
        try {
            this.factory = DocumentBuilderFactory.newInstance();
            this.factory.setNamespaceAware(true);
            this.factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            this.factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            this.factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            this.factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception e) {
            throw new IllegalStateException("Failed initializing XML DocumentBuilderFactory", e);
        }
    }

    public MsmlDocument parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new MsmlParseException("Cannot parse null or empty MSML content");
        }
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml.trim())));
            return parseDocument(doc);
        } catch (MsmlParseException e) {
            throw e;
        } catch (Exception e) {
            throw new MsmlParseException("Failed parsing MSML XML: " + e.getMessage(), e);
        }
    }

    public MsmlDocument parse(InputStream in) {
        if (in == null) {
            throw new MsmlParseException("InputStream must not be null");
        }
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(in);
            return parseDocument(doc);
        } catch (MsmlParseException e) {
            throw e;
        } catch (Exception e) {
            throw new MsmlParseException("Failed parsing MSML XML stream: " + e.getMessage(), e);
        }
    }

    private MsmlDocument parseDocument(Document doc) {
        Element root = doc.getDocumentElement();
        if (root == null || !"msml".equalsIgnoreCase(root.getLocalName() != null ? root.getLocalName() : root.getTagName())) {
            throw new MsmlParseException("Root element must be <msml>");
        }

        String version = getAttribute(root, "version").orElse(MsmlDocument.DEFAULT_VERSION);

        List<MsmlCommand> commands = new ArrayList<>();
        List<MsmlResult> results = new ArrayList<>();
        List<MsmlEvent> events = new ArrayList<>();

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            String tagName = getLocalTagName(elem).toLowerCase(Locale.ROOT);

            switch (tagName) {
                case "dialogstart" -> commands.add(parseDialogStart(elem));
                case "dialogend" -> commands.add(parseDialogEnd(elem));
                case "createconference" -> commands.add(parseCreateConference(elem));
                case "modifyconference" -> commands.add(parseModifyConference(elem));
                case "destroyconference" -> commands.add(parseDestroyConference(elem));
                case "join" -> commands.add(parseJoin(elem));
                case "unjoin" -> commands.add(parseUnjoin(elem));
                case "audit" -> commands.add(parseAudit(elem));
                case "result" -> results.add(parseResult(elem));
                case "event" -> events.add(parseEvent(elem));
                default -> {
                    // Ignore unrecognized top-level elements per extensibility rules
                }
            }
        }

        return new MsmlDocument(version, commands, results, events);
    }

    private MsmlCommand.DialogStart parseDialogStart(Element elem) {
        String targetRaw = getRequiredAttribute(elem, "target");
        MsmlTarget target = MsmlTarget.parse(targetRaw);
        String id = getAttribute(elem, "id").orElse(null);
        String type = getAttribute(elem, "type").orElse(null);

        // Check for child elements
        List<MomlElement> momlElements = new ArrayList<>();
        String vxmlUrl = null;
        String inlineVxml = null;

        NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            String tag = getLocalTagName(child).toLowerCase(Locale.ROOT);

            switch (tag) {
                case "play" -> momlElements.add(parsePlay(child));
                case "record" -> momlElements.add(parseRecord(child));
                case "dtmf" -> momlElements.add(parseDtmf(child));
                case "var" -> momlElements.add(parseVar(child));
                case "exit" -> momlElements.add(parseExit(child));
                case "sleep" -> momlElements.add(parseSleep(child));
                case "vxml" -> inlineVxml = nodeToXmlString(child);
                default -> {}
            }
        }

        MomlDialog dialog = !momlElements.isEmpty() ? new MomlDialog(momlElements) : null;
        return new MsmlCommand.DialogStart(id, target, type, dialog, vxmlUrl, inlineVxml);
    }

    private MsmlCommand.DialogEnd parseDialogEnd(Element elem) {
        String targetRaw = getRequiredAttribute(elem, "target");
        String id = getAttribute(elem, "id").orElse(null);
        return new MsmlCommand.DialogEnd(id, MsmlTarget.parse(targetRaw));
    }

    private MsmlCommand.CreateConference parseCreateConference(Element elem) {
        String confIdRaw = getAttribute(elem, "id")
                .or(() -> getAttribute(elem, "name"))
                .orElseThrow(() -> new MsmlParseException("<createconference> requires 'id' or 'name' attribute"));
        MsmlTarget target = MsmlTarget.conference(confIdRaw);
        String deleteWhen = getAttribute(elem, "deletewhen").orElse("nomedia");

        AudioMixConfig audioMix = parseAudioMix(elem);
        return new MsmlCommand.CreateConference(target, deleteWhen, audioMix);
    }

    private MsmlCommand.ModifyConference parseModifyConference(Element elem) {
        String idRaw = getRequiredAttribute(elem, "id");
        AudioMixConfig audioMix = parseAudioMix(elem);
        return new MsmlCommand.ModifyConference(MsmlTarget.conference(idRaw), audioMix);
    }

    private MsmlCommand.DestroyConference parseDestroyConference(Element elem) {
        String idRaw = getRequiredAttribute(elem, "id");
        return new MsmlCommand.DestroyConference(MsmlTarget.conference(idRaw));
    }

    private AudioMixConfig parseAudioMix(Element parent) {
        NodeList nl = parent.getElementsByTagNameNS("*", "audiomix");
        if (nl.getLength() == 0) {
            nl = parent.getElementsByTagName("audiomix");
        }
        if (nl.getLength() > 0) {
            Element mixElem = (Element) nl.item(0);
            String sampleRate = getAttribute(mixElem, "samplerate").orElse("8000");
            int channels = getAttribute(mixElem, "channels").map(Integer::parseInt).orElse(1);
            String gain = getAttribute(mixElem, "gain").orElse("0");
            return new AudioMixConfig(sampleRate, channels, gain);
        }
        return AudioMixConfig.standardPcm();
    }

    private MsmlCommand.Join parseJoin(Element elem) {
        String id1Raw = getRequiredAttribute(elem, "id1");
        String id2Raw = getRequiredAttribute(elem, "id2");
        MsmlTarget id1 = MsmlTarget.parse(id1Raw);
        MsmlTarget id2 = MsmlTarget.parse(id2Raw);

        StreamDirection dir = StreamDirection.SENDRECV;
        String media = "audio";

        NodeList nl = elem.getElementsByTagNameNS("*", "stream");
        if (nl.getLength() == 0) {
            nl = elem.getElementsByTagName("stream");
        }
        if (nl.getLength() > 0) {
            Element streamElem = (Element) nl.item(0);
            dir = getAttribute(streamElem, "dir").map(StreamDirection::fromString).orElse(StreamDirection.SENDRECV);
            media = getAttribute(streamElem, "media").orElse("audio");
        }

        return new MsmlCommand.Join(id1, id2, dir, media);
    }

    private MsmlCommand.Unjoin parseUnjoin(Element elem) {
        String id1Raw = getRequiredAttribute(elem, "id1");
        String id2Raw = getRequiredAttribute(elem, "id2");
        return new MsmlCommand.Unjoin(MsmlTarget.parse(id1Raw), MsmlTarget.parse(id2Raw));
    }

    private MsmlCommand.Audit parseAudit(Element elem) {
        String targetRaw = getRequiredAttribute(elem, "target");
        return new MsmlCommand.Audit(MsmlTarget.parse(targetRaw));
    }

    private MomlElement.Play parsePlay(Element elem) {
        String id = getAttribute(elem, "id").orElse(null);
        Duration offset = getAttribute(elem, "offset").map(MsmlParser::parseDuration).orElse(Duration.ZERO);
        Duration skip = getAttribute(elem, "skip").map(MsmlParser::parseDuration).orElse(Duration.ZERO);
        boolean barge = getAttribute(elem, "barge").map(Boolean::parseBoolean).orElse(true);
        boolean clearDb = getAttribute(elem, "cleardb").map(Boolean::parseBoolean).orElse(true);

        List<AudioSource> sources = new ArrayList<>();
        NodeList nl = elem.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            String tag = getLocalTagName(child).toLowerCase(Locale.ROOT);
            if ("audio".equals(tag) || "media".equals(tag)) {
                String uriStr = getRequiredAttribute(child, "uri");
                String format = getAttribute(child, "format").orElse("audio/wav");
                sources.add(AudioSource.of(uriStr, format));
            }
        }
        return new MomlElement.Play(id, offset, skip, barge, clearDb, sources);
    }

    private MomlElement.Record parseRecord(Element elem) {
        String id = getAttribute(elem, "id").orElse(null);
        URI dest = getAttribute(elem, "dest").map(URI::create).orElse(null);
        String format = getAttribute(elem, "format").orElse("audio/wav");
        Duration maxTime = getAttribute(elem, "maxtime").map(MsmlParser::parseDuration).orElse(Duration.ofSeconds(30));
        String termChar = getAttribute(elem, "termchar").orElse("#");
        boolean beep = getAttribute(elem, "beep").map(Boolean::parseBoolean).orElse(true);
        boolean append = getAttribute(elem, "append").map(Boolean::parseBoolean).orElse(false);

        return new MomlElement.Record(id, dest, format, maxTime, termChar, beep, append);
    }

    private MomlElement.Dtmf parseDtmf(Element elem) {
        String id = getAttribute(elem, "id").orElse(null);
        boolean clearDb = getAttribute(elem, "cleardb").map(Boolean::parseBoolean).orElse(true);
        Duration fdt = getAttribute(elem, "fdt").map(MsmlParser::parseDuration).orElse(Duration.ofSeconds(5));
        Duration idt = getAttribute(elem, "idt").map(MsmlParser::parseDuration).orElse(Duration.ofSeconds(3));
        Duration edt = getAttribute(elem, "edt").map(MsmlParser::parseDuration).orElse(Duration.ofSeconds(2));

        DtmfPattern pattern = DtmfPattern.ofSingleDigit();
        NodeList nl = elem.getElementsByTagNameNS("*", "pattern");
        if (nl.getLength() == 0) {
            nl = elem.getElementsByTagName("pattern");
        }
        if (nl.getLength() > 0) {
            Element patternElem = (Element) nl.item(0);
            String digits = getAttribute(patternElem, "digits").orElse("");
            pattern = DtmfPattern.parse(digits);
        }

        return new MomlElement.Dtmf(id, clearDb, fdt, idt, edt, pattern);
    }

    private MomlElement.Var parseVar(Element elem) {
        String name = getRequiredAttribute(elem, "name");
        String value = getAttribute(elem, "value").orElse("");
        return new MomlElement.Var(name, value);
    }

    private MomlElement.Exit parseExit(Element elem) {
        String nameList = getAttribute(elem, "namelist").orElse(null);
        return new MomlElement.Exit(nameList);
    }

    private MomlElement.Sleep parseSleep(Element elem) {
        Duration dur = getAttribute(elem, "duration").map(MsmlParser::parseDuration).orElse(Duration.ZERO);
        return new MomlElement.Sleep(dur);
    }

    private MsmlResult parseResult(Element elem) {
        String id = getAttribute(elem, "id").orElse(null);
        int response = getAttribute(elem, "response").map(Integer::parseInt).orElse(200);
        String message = getAttribute(elem, "message").orElse("OK");

        Map<String, String> data = new LinkedHashMap<>();
        NodeList nl = elem.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            data.put(getLocalTagName(child), child.getTextContent().trim());
        }

        return new MsmlResult(id, response, message, data);
    }

    private MsmlEvent parseEvent(Element elem) {
        String name = getRequiredAttribute(elem, "name");
        String id = getAttribute(elem, "id").orElse(null);

        Map<String, String> params = new LinkedHashMap<>();
        NamedNodeMap attrs = elem.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (!"name".equalsIgnoreCase(a.getNodeName()) && !"id".equalsIgnoreCase(a.getNodeName())) {
                params.put(a.getNodeName(), a.getNodeValue());
            }
        }

        NodeList nl = elem.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            params.put(getLocalTagName(child), child.getTextContent().trim());
        }

        return new MsmlEvent(name, id, params);
    }

    public static Duration parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return Duration.ZERO;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2).trim()));
            } else if (s.endsWith("s")) {
                return Duration.ofMillis((long) (Double.parseDouble(s.substring(0, s.length() - 1).trim()) * 1000));
            } else if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            } else {
                return Duration.ofMillis((long) (Double.parseDouble(s) * 1000));
            }
        } catch (Exception e) {
            return Duration.ZERO;
        }
    }

    private static Optional<String> getAttribute(Element elem, String name) {
        if (elem.hasAttribute(name)) {
            return Optional.of(elem.getAttribute(name).trim());
        }
        return Optional.empty();
    }

    private static String getRequiredAttribute(Element elem, String name) {
        if (!elem.hasAttribute(name) || elem.getAttribute(name).isBlank()) {
            throw new MsmlParseException("Element <" + getLocalTagName(elem) + "> missing required attribute '" + name + "'");
        }
        return elem.getAttribute(name).trim();
    }

    private static String getLocalTagName(Element elem) {
        String local = elem.getLocalName();
        return local != null && !local.isBlank() ? local : elem.getTagName();
    }

    private static String nodeToXmlString(Node node) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(node), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
