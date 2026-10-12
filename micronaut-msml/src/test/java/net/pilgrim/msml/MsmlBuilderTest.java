package net.pilgrim.msml;

import net.pilgrim.msml.builder.MsmlBuilder;
import net.pilgrim.msml.builder.MsmlSerializer;
import net.pilgrim.msml.model.MsmlCommand;
import net.pilgrim.msml.model.MsmlDocument;
import net.pilgrim.msml.model.StreamDirection;
import net.pilgrim.msml.model.moml.MomlElement;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;
import net.pilgrim.msml.parser.MsmlParser;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MsmlBuilderTest {

    private final MsmlParser parser = new MsmlParser();

    @Test
    void buildAndSerializePlayDialog() {
        MsmlDocument doc = MsmlBuilder.dialogStart("conn:call-123")
                .id("play-dlg")
                .play("file:///var/prompts/hello.wav", true)
                .sleep(Duration.ofSeconds(1))
                .build();

        String xml = MsmlSerializer.serialize(doc);
        assertNotNull(xml);
        assertTrue(xml.contains("<dialogstart target=\"conn:call-123\" id=\"play-dlg\""));
        assertTrue(xml.contains("<play barge=\"true\" cleardb=\"true\">"));
        assertTrue(xml.contains("<audio uri=\"file:///var/prompts/hello.wav\""));
        assertTrue(xml.contains("<sleep duration=\"1s\"/>"));

        // Roundtrip parse
        MsmlDocument parsed = parser.parse(xml);
        assertEquals(1, parsed.commands().size());
        MsmlCommand.DialogStart ds = (MsmlCommand.DialogStart) parsed.commands().getFirst();
        assertEquals("play-dlg", ds.id());
        assertEquals("conn:call-123", ds.target().toString());
        assertEquals(2, ds.momlDialog().size());
        assertInstanceOf(MomlElement.Play.class, ds.momlDialog().elements().get(0));
        assertInstanceOf(MomlElement.Sleep.class, ds.momlDialog().elements().get(1));
    }

    @Test
    void buildAndSerializeRecordAndDtmf() {
        MsmlDocument doc = MsmlBuilder.dialogStart("conn:call-456")
                .id("rec-dlg")
                .record(URI.create("file:///tmp/rec.wav"), Duration.ofSeconds(45), "#", true)
                .dtmf(1, 4, "#")
                .exit("rec_bytes rec_termchar")
                .build();

        String xml = MsmlSerializer.serialize(doc);
        assertTrue(xml.contains("<record id=\"\" dest=\"file:///tmp/rec.wav\" format=\"audio/wav\" maxtime=\"45s\" termchar=\"#\" beep=\"true\" append=\"false\"/>")
                || xml.contains("dest=\"file:///tmp/rec.wav\""));
        assertTrue(xml.contains("<pattern digits=\"min=1;max=4;rtk=#\"/>"));
        assertTrue(xml.contains("<exit namelist=\"rec_bytes rec_termchar\"/>"));

        MsmlDocument parsed = parser.parse(xml);
        MsmlCommand.DialogStart ds = (MsmlCommand.DialogStart) parsed.commands().getFirst();
        assertEquals(3, ds.momlDialog().size());
        MomlElement.Record rec = (MomlElement.Record) ds.momlDialog().elements().get(0);
        assertEquals(Duration.ofSeconds(45), rec.maxTime());
        assertEquals("#", rec.termChar());
    }

    @Test
    void buildAndSerializeConferenceJoin() {
        MsmlDocument createConf = MsmlBuilder.createConference("conf:bridge-1")
                .deleteWhen("nomedia")
                .sampleRate("16000")
                .channels(1)
                .gain("0")
                .build();

        String xmlConf = MsmlSerializer.serialize(createConf);
        assertTrue(xmlConf.contains("<createconference id=\"bridge-1\" deletewhen=\"nomedia\">"));
        assertTrue(xmlConf.contains("<audiomix samplerate=\"16000\" channels=\"1\" gain=\"0\"/>"));

        MsmlDocument parsedConf = parser.parse(xmlConf);
        MsmlCommand.CreateConference cc = (MsmlCommand.CreateConference) parsedConf.commands().getFirst();
        assertEquals("bridge-1", cc.target().id());
        assertEquals("16000", cc.audioMix().sampleRate());

        MsmlDocument join = MsmlBuilder.join("conn:call-1", "conf:bridge-1")
                .direction(StreamDirection.SENDRECV)
                .media("audio")
                .build();

        String xmlJoin = MsmlSerializer.serialize(join);
        assertTrue(xmlJoin.contains("<join id1=\"conn:call-1\" id2=\"conf:bridge-1\">"));
        assertTrue(xmlJoin.contains("<stream dir=\"sendrecv\" media=\"audio\"/>"));

        MsmlDocument parsedJoin = parser.parse(xmlJoin);
        MsmlCommand.Join j = (MsmlCommand.Join) parsedJoin.commands().getFirst();
        assertEquals(StreamDirection.SENDRECV, j.direction());
    }

    @Test
    void buildAndSerializeResultAndEvent() {
        MsmlDocument res = MsmlBuilder.result(200, "OK")
                .id("dlg-99")
                .data("digits", "1234")
                .data("reason", "termchar")
                .build();

        String xmlRes = MsmlSerializer.serialize(res);
        assertTrue(xmlRes.contains("<result response=\"200\" message=\"OK\" id=\"dlg-99\">"));
        assertTrue(xmlRes.contains("<digits>1234</digits>"));
        assertTrue(xmlRes.contains("<reason>termchar</reason>"));

        MsmlDocument parsedRes = parser.parse(xmlRes);
        MsmlResult r = parsedRes.results().getFirst();
        assertEquals(200, r.responseCode());
        assertEquals("1234", r.data().get("digits"));

        MsmlDocument evt = MsmlBuilder.event(MsmlEvent.RECORD_COMPLETE, "dlg-99")
                .param("dest", "file:///tmp/rec.wav")
                .param("bytes", "32000")
                .param("termchar", "#")
                .build();

        String xmlEvt = MsmlSerializer.serialize(evt);
        assertTrue(xmlEvt.contains("<event name=\"msml.moml.record.complete\" id=\"dlg-99\">"));
        assertTrue(xmlEvt.contains("<dest>file:///tmp/rec.wav</dest>"));

        MsmlDocument parsedEvt = parser.parse(xmlEvt);
        MsmlEvent e = parsedEvt.events().getFirst();
        assertEquals(MsmlEvent.RECORD_COMPLETE, e.name());
        assertEquals("32000", e.params().get("bytes"));
    }
}
