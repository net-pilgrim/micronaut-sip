package net.pilgrim.ccxml;

import net.pilgrim.ccxml.ast.*;
import net.pilgrim.ccxml.bridge.DuplexMode;
import net.pilgrim.ccxml.parser.CcxmlParseException;
import net.pilgrim.ccxml.parser.CcxmlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CcxmlParserTest {

    private final CcxmlParser parser = new CcxmlParser();

    @Test
    void testParseBasicCcxml() {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ccxml version="1.0">
                <var name="myVar" expr="'hello'"/>
                <var name="count" expr="42"/>
                <script>
                    var initFlag = true;
                </script>
                <eventprocessor statevariable="myState">
                    <transition event="connection.alerting" state="init">
                        <accept/>
                    </transition>
                    <transition event="connection.connected">
                        <dialogstart src="'ivr.vxml'" dialogid="dlg1"/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlDocument doc = parser.parse(xml);
        assertNotNull(doc);
        assertEquals("1.0", doc.version());
        assertEquals(2, doc.vars().size());
        assertEquals("myVar", doc.vars().get(0).name());
        assertEquals("'hello'", doc.vars().get(0).expr());
        assertEquals("count", doc.vars().get(1).name());
        assertEquals("42", doc.vars().get(1).expr());

        assertEquals(1, doc.scripts().size());
        assertTrue(doc.scripts().get(0).content().contains("initFlag = true"));

        CcxmlEventProcessor ep = doc.eventProcessor();
        assertEquals("myState", ep.statevariable());
        assertEquals(2, ep.transitions().size());

        CcxmlTransition t1 = ep.transitions().get(0);
        assertEquals("connection.alerting", t1.event());
        assertEquals("init", t1.state());
        assertEquals(1, t1.executables().size());
        assertInstanceOf(CcxmlAccept.class, t1.executables().get(0));

        CcxmlTransition t2 = ep.transitions().get(1);
        assertEquals("connection.connected", t2.event());
        assertNull(t2.state());
        assertInstanceOf(CcxmlDialogStart.class, t2.executables().get(0));
    }

    @Test
    void testParseIfElseIfElseTree() {
        String xml = """
            <ccxml version="1.0">
                <eventprocessor>
                    <transition event="dialog.exit">
                        <if cond="event$.values.target == 'agent'">
                            <createcall dest="'sip:agent@pbx'" connectionid="agentConn"/>
                        <elseif cond="event$.values.target == 'voicemail'"/>
                            <dialogstart src="'voicemail.vxml'"/>
                        <else/>
                            <disconnect/>
                        </if>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlDocument doc = parser.parse(xml);
        CcxmlTransition t = doc.eventProcessor().transitions().get(0);
        assertEquals(1, t.executables().size());
        assertInstanceOf(CcxmlIf.class, t.executables().get(0));

        CcxmlIf ifNode = (CcxmlIf) t.executables().get(0);
        assertEquals("event$.values.target == 'agent'", ifNode.cond());
        assertEquals(1, ifNode.thenExecutables().size());
        assertInstanceOf(CcxmlCreateCall.class, ifNode.thenExecutables().get(0));

        assertEquals(1, ifNode.elseIfs().size());
        CcxmlElseIf elseIf = ifNode.elseIfs().get(0);
        assertEquals("event$.values.target == 'voicemail'", elseIf.cond());
        assertInstanceOf(CcxmlDialogStart.class, elseIf.executables().get(0));

        assertNotNull(ifNode.elseBlock());
        assertInstanceOf(CcxmlDisconnect.class, ifNode.elseBlock().executables().get(0));
    }

    @Test
    void testParseConferenceAndJoin() {
        String xml = """
            <ccxml version="1.0">
                <eventprocessor>
                    <transition event="init">
                        <createconference conferenceid="confId" confname="'room_101'" reservedtalkers="10"/>
                        <join id1="conn1" id2="confId" duplex="half"/>
                        <unjoin id1="conn1" id2="confId"/>
                        <destroyconference conferenceid="confId"/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlDocument doc = parser.parse(xml);
        CcxmlTransition t = doc.eventProcessor().transitions().get(0);
        assertEquals(4, t.executables().size());

        CcxmlCreateConference createConf = (CcxmlCreateConference) t.executables().get(0);
        assertEquals("confId", createConf.conferenceid());
        assertEquals("'room_101'", createConf.confname());
        assertEquals(10, createConf.reservedtalkers());

        CcxmlJoin join = (CcxmlJoin) t.executables().get(1);
        assertEquals("conn1", join.id1());
        assertEquals("confId", join.id2());
        assertEquals(DuplexMode.HALF, join.duplex());

        CcxmlUnjoin unjoin = (CcxmlUnjoin) t.executables().get(2);
        assertEquals("conn1", unjoin.id1());
        assertEquals("confId", unjoin.id2());

        CcxmlDestroyConference destroyConf = (CcxmlDestroyConference) t.executables().get(3);
        assertEquals("confId", destroyConf.conferenceid());
    }

    @Test
    void testInvalidXmlThrowsParseException() {
        assertThrows(CcxmlParseException.class, () -> parser.parse(""));
        assertThrows(CcxmlParseException.class, () -> parser.parse("<invalidRoot/>"));
    }
}
