package net.pilgrim.msml;

import net.pilgrim.msml.builder.MsmlSerializer;
import net.pilgrim.msml.model.*;
import net.pilgrim.msml.model.moml.*;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;
import net.pilgrim.msml.parser.MsmlParseException;
import net.pilgrim.msml.parser.MsmlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MsmlParserTest {

    private MsmlParser parser;

    @BeforeEach
    void setUp() {
        parser = new MsmlParser();
    }

    @Test
    void parsePlayCommand() {
        String xml = """
                <msml version="1.1">
                  <dialogstart target="conn:call-101" id="dlg-play-1">
                    <play barge="true" cleardb="true" offset="500ms">
                      <audio uri="file:///tmp/welcome.wav" format="audio/wav"/>
                    </play>
                  </dialogstart>
                </msml>
                """;

        MsmlDocument doc = parser.parse(xml);
        assertNotNull(doc);
        assertEquals("1.1", doc.version());
        assertEquals(1, doc.commands().size());

        MsmlCommand cmd = doc.commands().getFirst();
        assertInstanceOf(MsmlCommand.DialogStart.class, cmd);

        MsmlCommand.DialogStart ds = (MsmlCommand.DialogStart) cmd;
        assertEquals("dlg-play-1", ds.id());
        assertEquals("conn:call-101", ds.target().toString());
        assertEquals("call-101", ds.target().id());
        assertTrue(ds.isMoml());
        assertNotNull(ds.momlDialog());
        assertEquals(1, ds.momlDialog().size());

        MomlElement element = ds.momlDialog().elements().getFirst();
        assertInstanceOf(MomlElement.Play.class, element);
        MomlElement.Play play = (MomlElement.Play) element;
        assertTrue(play.barge());
        assertTrue(play.clearDb());
        assertEquals(Duration.ofMillis(500), play.offset());
        assertEquals(1, play.sources().size());
        assertEquals(URI.create("file:///tmp/welcome.wav"), play.sources().getFirst().uri());
    }

    @Test
    void parseRecordAndDtmfCommand() {
        String xml = """
                <msml version="1.1">
                  <dialogstart target="conn:user-42" id="dlg-rec">
                    <record dest="file:///tmp/msg.wav" format="audio/wav" maxtime="30s" termchar="#" beep="true"/>
                    <dtmf cleardb="true" fdt="5s" idt="3s" edt="2s">
                      <pattern digits="min=1;max=4;rtk=#;ctk=*"/>
                    </dtmf>
                    <sleep duration="1s"/>
                    <exit namelist="var1 var2"/>
                  </dialogstart>
                </msml>
                """;

        MsmlDocument doc = parser.parse(xml);
        MsmlCommand.DialogStart ds = (MsmlCommand.DialogStart) doc.commands().getFirst();
        assertNotNull(ds.momlDialog());
        assertEquals(4, ds.momlDialog().size());

        MomlElement.Record rec = (MomlElement.Record) ds.momlDialog().elements().get(0);
        assertEquals(URI.create("file:///tmp/msg.wav"), rec.dest());
        assertEquals(Duration.ofSeconds(30), rec.maxTime());
        assertEquals("#", rec.termChar());
        assertTrue(rec.beep());

        MomlElement.Dtmf dtmf = (MomlElement.Dtmf) ds.momlDialog().elements().get(1);
        assertEquals(Duration.ofSeconds(5), dtmf.fdt());
        assertEquals(Duration.ofSeconds(3), dtmf.idt());
        assertEquals(Duration.ofSeconds(2), dtmf.edt());
        assertEquals(1, dtmf.pattern().minDigits());
        assertEquals(4, dtmf.pattern().maxDigits());
        assertEquals("#", dtmf.pattern().returnKey());
        assertEquals("*", dtmf.pattern().cancelKey());

        MomlElement.Sleep sleep = (MomlElement.Sleep) ds.momlDialog().elements().get(2);
        assertEquals(Duration.ofSeconds(1), sleep.duration());

        MomlElement.Exit exit = (MomlElement.Exit) ds.momlDialog().elements().get(3);
        assertEquals("var1 var2", exit.nameList());
    }

    @Test
    void parseConferenceAndJoinCommands() {
        String xml = """
                <msml version="1.1">
                  <createconference id="conf:room101" deletewhen="nomedia">
                    <audiomix samplerate="8000" channels="1" gain="0"/>
                  </createconference>
                  <join id1="conn:call-1" id2="conf:room101">
                    <stream dir="sendrecv" media="audio"/>
                  </join>
                  <unjoin id1="conn:call-1" id2="conf:room101"/>
                  <destroyconference id="conf:room101"/>
                </msml>
                """;

        MsmlDocument doc = parser.parse(xml);
        assertEquals(4, doc.commands().size());

        MsmlCommand.CreateConference cc = (MsmlCommand.CreateConference) doc.commands().get(0);
        assertEquals("conf:room101", cc.target().toString());
        assertEquals("nomedia", cc.deleteWhen());
        assertEquals("8000", cc.audioMix().sampleRate());

        MsmlCommand.Join join = (MsmlCommand.Join) doc.commands().get(1);
        assertEquals("conn:call-1", join.id1().toString());
        assertEquals("conf:room101", join.id2().toString());
        assertEquals(StreamDirection.SENDRECV, join.direction());

        MsmlCommand.Unjoin unjoin = (MsmlCommand.Unjoin) doc.commands().get(2);
        assertEquals("conn:call-1", unjoin.id1().toString());
        assertEquals("conf:room101", unjoin.id2().toString());

        MsmlCommand.DestroyConference dc = (MsmlCommand.DestroyConference) doc.commands().get(3);
        assertEquals("conf:room101", dc.target().toString());
    }

    @Test
    void parseResultAndEvent() {
        String xml = """
                <msml version="1.1">
                  <result response="200" message="OK" id="dlg-1">
                    <termchar>#</termchar>
                    <duration>12500</duration>
                  </result>
                  <event name="msml.dialog.exit" id="dlg-1">
                    <reason>user_hangup</reason>
                  </event>
                </msml>
                """;

        MsmlDocument doc = parser.parse(xml);
        assertTrue(doc.isResult());
        assertTrue(doc.isEvent());
        assertEquals(1, doc.results().size());
        assertEquals(1, doc.events().size());

        MsmlResult result = doc.results().getFirst();
        assertEquals(200, result.responseCode());
        assertEquals("OK", result.message());
        assertEquals("dlg-1", result.id());
        assertEquals("#", result.data().get("termchar"));
        assertEquals("12500", result.data().get("duration"));

        MsmlEvent event = doc.events().getFirst();
        assertEquals("msml.dialog.exit", event.name());
        assertEquals("dlg-1", event.id());
        assertEquals("user_hangup", event.params().get("reason"));
    }

    @Test
    void parseInlineVoiceXml() {
        String xml = """
                <msml version="1.1">
                  <dialogstart target="conn:session-99" id="vxml-dlg" type="application/voicexml+xml">
                    <vxml version="2.1">
                      <form id="welcome">
                        <block>
                          <prompt>Welcome to MSML VoiceXML integration</prompt>
                        </block>
                      </form>
                    </vxml>
                  </dialogstart>
                </msml>
                """;

        MsmlDocument doc = parser.parse(xml);
        MsmlCommand.DialogStart ds = (MsmlCommand.DialogStart) doc.commands().getFirst();
        assertTrue(ds.isVoiceXml());
        assertNotNull(ds.inlineVxml());
        assertTrue(ds.inlineVxml().contains("<vxml"));
        assertTrue(ds.inlineVxml().contains("Welcome to MSML VoiceXML integration"));
    }

    @Test
    void parseInvalidXmlThrows() {
        assertThrows(MsmlParseException.class, () -> parser.parse(""));
        assertThrows(MsmlParseException.class, () -> parser.parse("<notmsml></notmsml>"));
    }
}
