package net.pilgrim.msml.builder;

import net.pilgrim.msml.model.*;
import net.pilgrim.msml.model.moml.*;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Serializes MSML AST objects to RFC 5707 compliant XML representations.
 */
public final class MsmlSerializer {

    private MsmlSerializer() {}

    public static String serialize(MsmlDocument doc) {
        Objects.requireNonNull(doc, "doc must not be null");
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<msml version=\"").append(escapeXml(doc.version())).append("\">\n");

        for (MsmlCommand cmd : doc.commands()) {
            serializeCommand(sb, cmd, "  ");
        }
        for (MsmlResult res : doc.results()) {
            serializeResult(sb, res, "  ");
        }
        for (MsmlEvent evt : doc.events()) {
            serializeEvent(sb, evt, "  ");
        }

        sb.append("</msml>");
        return sb.toString();
    }

    public static String serialize(MsmlCommand cmd) {
        return serialize(MsmlDocument.ofCommand(cmd));
    }

    public static String serialize(MsmlResult res) {
        return serialize(MsmlDocument.ofResult(res));
    }

    public static String serialize(MsmlEvent evt) {
        return serialize(MsmlDocument.ofEvent(evt));
    }

    private static void serializeCommand(StringBuilder sb, MsmlCommand cmd, String indent) {
        switch (cmd) {
            case MsmlCommand.DialogStart ds -> serializeDialogStart(sb, ds, indent);
            case MsmlCommand.DialogEnd de -> serializeDialogEnd(sb, de, indent);
            case MsmlCommand.CreateConference cc -> serializeCreateConference(sb, cc, indent);
            case MsmlCommand.ModifyConference mc -> serializeModifyConference(sb, mc, indent);
            case MsmlCommand.DestroyConference dc -> serializeDestroyConference(sb, dc, indent);
            case MsmlCommand.Join j -> serializeJoin(sb, j, indent);
            case MsmlCommand.Unjoin uj -> serializeUnjoin(sb, uj, indent);
            case MsmlCommand.Audit a -> serializeAudit(sb, a, indent);
        }
    }

    private static void serializeDialogStart(StringBuilder sb, MsmlCommand.DialogStart ds, String indent) {
        sb.append(indent).append("<dialogstart target=\"").append(escapeXml(ds.target().toString())).append("\"");
        if (ds.id() != null && !ds.id().isBlank()) {
            sb.append(" id=\"").append(escapeXml(ds.id())).append("\"");
        }
        if (ds.type() != null && !ds.type().isBlank()) {
            sb.append(" type=\"").append(escapeXml(ds.type())).append("\"");
        }
        sb.append(">\n");

        if (ds.isVoiceXml()) {
            if (ds.inlineVxml() != null && !ds.inlineVxml().isBlank()) {
                sb.append(indent).append("  ").append(ds.inlineVxml().trim()).append("\n");
            }
        } else if (ds.momlDialog() != null) {
            for (MomlElement el : ds.momlDialog().elements()) {
                serializeMomlElement(sb, el, indent + "  ");
            }
        }

        sb.append(indent).append("</dialogstart>\n");
    }

    private static void serializeDialogEnd(StringBuilder sb, MsmlCommand.DialogEnd de, String indent) {
        sb.append(indent).append("<dialogend target=\"").append(escapeXml(de.target().toString())).append("\"");
        if (de.id() != null && !de.id().isBlank()) {
            sb.append(" id=\"").append(escapeXml(de.id())).append("\"");
        }
        sb.append("/>\n");
    }

    private static void serializeCreateConference(StringBuilder sb, MsmlCommand.CreateConference cc, String indent) {
        sb.append(indent).append("<createconference id=\"").append(escapeXml(cc.target().id())).append("\"");
        if (cc.deleteWhen() != null && !cc.deleteWhen().isBlank()) {
            sb.append(" deletewhen=\"").append(escapeXml(cc.deleteWhen())).append("\"");
        }
        sb.append(">\n");
        if (cc.audioMix() != null) {
            serializeAudioMix(sb, cc.audioMix(), indent + "  ");
        }
        sb.append(indent).append("</createconference>\n");
    }

    private static void serializeModifyConference(StringBuilder sb, MsmlCommand.ModifyConference mc, String indent) {
        sb.append(indent).append("<modifyconference id=\"").append(escapeXml(mc.target().id())).append("\">\n");
        if (mc.audioMix() != null) {
            serializeAudioMix(sb, mc.audioMix(), indent + "  ");
        }
        sb.append(indent).append("</modifyconference>\n");
    }

    private static void serializeDestroyConference(StringBuilder sb, MsmlCommand.DestroyConference dc, String indent) {
        sb.append(indent).append("<destroyconference id=\"").append(escapeXml(dc.target().id())).append("\"/>\n");
    }

    private static void serializeJoin(StringBuilder sb, MsmlCommand.Join j, String indent) {
        sb.append(indent).append("<join id1=\"").append(escapeXml(j.id1().toString())).append("\" id2=\"")
                .append(escapeXml(j.id2().toString())).append("\">\n");
        sb.append(indent).append("  <stream dir=\"").append(j.direction().getValue()).append("\" media=\"")
                .append(escapeXml(j.media())).append("\"/>\n");
        sb.append(indent).append("</join>\n");
    }

    private static void serializeUnjoin(StringBuilder sb, MsmlCommand.Unjoin uj, String indent) {
        sb.append(indent).append("<unjoin id1=\"").append(escapeXml(uj.id1().toString())).append("\" id2=\"")
                .append(escapeXml(uj.id2().toString())).append("\"/>\n");
    }

    private static void serializeAudit(StringBuilder sb, MsmlCommand.Audit a, String indent) {
        sb.append(indent).append("<audit target=\"").append(escapeXml(a.target().toString())).append("\"/>\n");
    }

    private static void serializeAudioMix(StringBuilder sb, AudioMixConfig mix, String indent) {
        sb.append(indent).append("<audiomix samplerate=\"").append(escapeXml(mix.sampleRate()))
                .append("\" channels=\"").append(mix.channels())
                .append("\" gain=\"").append(escapeXml(mix.gain())).append("\"/>\n");
    }

    private static void serializeMomlElement(StringBuilder sb, MomlElement element, String indent) {
        switch (element) {
            case MomlElement.Play p -> serializePlay(sb, p, indent);
            case MomlElement.Record r -> serializeRecord(sb, r, indent);
            case MomlElement.Dtmf d -> serializeDtmf(sb, d, indent);
            case MomlElement.Var v -> serializeVar(sb, v, indent);
            case MomlElement.Sleep s -> serializeSleep(sb, s, indent);
            case MomlElement.Exit e -> serializeExit(sb, e, indent);
        }
    }

    private static void serializePlay(StringBuilder sb, MomlElement.Play p, String indent) {
        sb.append(indent).append("<play");
        if (p.id() != null && !p.id().isBlank()) {
            sb.append(" id=\"").append(escapeXml(p.id())).append("\"");
        }
        if (!p.offset().isZero()) {
            sb.append(" offset=\"").append(formatDuration(p.offset())).append("\"");
        }
        if (!p.skip().isZero()) {
            sb.append(" skip=\"").append(formatDuration(p.skip())).append("\"");
        }
        sb.append(" barge=\"").append(p.barge()).append("\"");
        sb.append(" cleardb=\"").append(p.clearDb()).append("\">\n");

        for (AudioSource src : p.sources()) {
            sb.append(indent).append("  <audio uri=\"").append(escapeXml(src.uri().toString())).append("\"");
            if (src.format() != null && !src.format().isBlank()) {
                sb.append(" format=\"").append(escapeXml(src.format())).append("\"");
            }
            sb.append("/>\n");
        }
        sb.append(indent).append("</play>\n");
    }

    private static void serializeRecord(StringBuilder sb, MomlElement.Record r, String indent) {
        sb.append(indent).append("<record");
        if (r.id() != null && !r.id().isBlank()) {
            sb.append(" id=\"").append(escapeXml(r.id())).append("\"");
        }
        if (r.dest() != null) {
            sb.append(" dest=\"").append(escapeXml(r.dest().toString())).append("\"");
        }
        if (r.format() != null && !r.format().isBlank()) {
            sb.append(" format=\"").append(escapeXml(r.format())).append("\"");
        }
        sb.append(" maxtime=\"").append(formatDuration(r.maxTime())).append("\"");
        sb.append(" termchar=\"").append(escapeXml(r.termChar())).append("\"");
        sb.append(" beep=\"").append(r.beep()).append("\"");
        sb.append(" append=\"").append(r.append()).append("\"/>\n");
    }

    private static void serializeDtmf(StringBuilder sb, MomlElement.Dtmf d, String indent) {
        sb.append(indent).append("<dtmf");
        if (d.id() != null && !d.id().isBlank()) {
            sb.append(" id=\"").append(escapeXml(d.id())).append("\"");
        }
        sb.append(" cleardb=\"").append(d.clearDb()).append("\"");
        sb.append(" fdt=\"").append(formatDuration(d.fdt())).append("\"");
        sb.append(" idt=\"").append(formatDuration(d.idt())).append("\"");
        sb.append(" edt=\"").append(formatDuration(d.edt())).append("\">\n");

        if (d.pattern() != null) {
            sb.append(indent).append("  <pattern digits=\"min=").append(d.pattern().minDigits())
                    .append(";max=").append(d.pattern().maxDigits());
            if (d.pattern().returnKey() != null) {
                sb.append(";rtk=").append(escapeXml(d.pattern().returnKey()));
            }
            if (d.pattern().cancelKey() != null) {
                sb.append(";ctk=").append(escapeXml(d.pattern().cancelKey()));
            }
            sb.append("\"/>\n");
        }
        sb.append(indent).append("</dtmf>\n");
    }

    private static void serializeVar(StringBuilder sb, MomlElement.Var v, String indent) {
        sb.append(indent).append("<var name=\"").append(escapeXml(v.name())).append("\" value=\"")
                .append(escapeXml(v.value())).append("\"/>\n");
    }

    private static void serializeSleep(StringBuilder sb, MomlElement.Sleep s, String indent) {
        sb.append(indent).append("<sleep duration=\"").append(formatDuration(s.duration())).append("\"/>\n");
    }

    private static void serializeExit(StringBuilder sb, MomlElement.Exit e, String indent) {
        sb.append(indent).append("<exit");
        if (e.nameList() != null && !e.nameList().isBlank()) {
            sb.append(" namelist=\"").append(escapeXml(e.nameList())).append("\"");
        }
        sb.append("/>\n");
    }

    public static void serializeResult(StringBuilder sb, MsmlResult res, String indent) {
        sb.append(indent).append("<result response=\"").append(res.responseCode()).append("\" message=\"")
                .append(escapeXml(res.message())).append("\"");
        if (res.id() != null && !res.id().isBlank()) {
            sb.append(" id=\"").append(escapeXml(res.id())).append("\"");
        }
        if (res.data().isEmpty()) {
            sb.append("/>\n");
        } else {
            sb.append(">\n");
            for (Map.Entry<String, String> entry : res.data().entrySet()) {
                sb.append(indent).append("  <").append(escapeXml(entry.getKey())).append(">")
                        .append(escapeXml(entry.getValue()))
                        .append("</").append(escapeXml(entry.getKey())).append(">\n");
            }
            sb.append(indent).append("</result>\n");
        }
    }

    public static void serializeEvent(StringBuilder sb, MsmlEvent evt, String indent) {
        sb.append(indent).append("<event name=\"").append(escapeXml(evt.name())).append("\"");
        if (evt.id() != null && !evt.id().isBlank()) {
            sb.append(" id=\"").append(escapeXml(evt.id())).append("\"");
        }
        if (evt.params().isEmpty()) {
            sb.append("/>\n");
        } else {
            sb.append(">\n");
            for (Map.Entry<String, String> entry : evt.params().entrySet()) {
                sb.append(indent).append("  <").append(escapeXml(entry.getKey())).append(">")
                        .append(escapeXml(entry.getValue()))
                        .append("</").append(escapeXml(entry.getKey())).append(">\n");
            }
            sb.append(indent).append("</event>\n");
        }
    }

    public static String formatDuration(Duration duration) {
        if (duration == null || duration.isZero()) {
            return "0s";
        }
        long millis = duration.toMillis();
        if (millis % 1000 == 0) {
            long seconds = millis / 1000;
            if (seconds >= 60 && seconds % 60 == 0) {
                return (seconds / 60) + "m";
            }
            return seconds + "s";
        }
        return millis + "ms";
    }

    public static String escapeXml(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
