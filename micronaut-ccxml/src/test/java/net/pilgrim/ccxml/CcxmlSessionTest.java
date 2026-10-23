package net.pilgrim.ccxml;

import net.pilgrim.ccxml.ast.CcxmlDocument;
import net.pilgrim.ccxml.bridge.InMemoryConnectionBridge;
import net.pilgrim.ccxml.bridge.InMemoryDialogBridge;
import net.pilgrim.ccxml.bridge.InMemoryMediaBridge;
import net.pilgrim.ccxml.parser.CcxmlParser;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CcxmlSessionTest {

    private final CcxmlParser parser = new CcxmlParser();

    @Test
    void testVariableInitializationAndScoping() {
        String xml = """
            <ccxml version="1.0">
                <var name="counter" expr="10"/>
                <var name="message" expr="'welcome'"/>
                <var name="uninitialized"/>
                <script>
                    counter = counter + 5;
                    initializedInScript = true;
                </script>
                <eventprocessor>
                    <transition event="test.event">
                        <assign name="counter" expr="counter * 2"/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlDocument doc = parser.parse(xml);
        CcxmlSession session = new CcxmlSession(doc);

        assertEquals(15L, ((Number) session.getVariable("counter")).longValue());
        assertEquals("welcome", session.getVariable("message"));
        assertEquals("", session.getVariable("uninitialized"));
        assertEquals(true, session.getVariable("initializedInScript"));

        session.processEvent(new CcxmlEvent("test.event"));
        assertEquals(30L, ((Number) session.getVariable("counter")).longValue());
    }

    @Test
    void testPrefixAndWildcardEventMatching() {
        String xml = """
            <ccxml version="1.0">
                <var name="matchedEvent"/>
                <eventprocessor>
                    <transition event="connection.*">
                        <assign name="matchedEvent" expr="event$.name"/>
                    </transition>
                    <transition event="*">
                        <assign name="matchedEvent" expr="'fallback'"/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlDocument doc = parser.parse(xml);
        CcxmlSession session = new CcxmlSession(doc);

        session.processEvent(new CcxmlEvent("connection.connected"));
        assertEquals("connection.connected", session.getVariable("matchedEvent"));

        session.processEvent(new CcxmlEvent("user.ping"));
        assertEquals("fallback", session.getVariable("matchedEvent"));
    }

    @Test
    void testConditionalTransitionMatching() {
        String xml = """
            <ccxml version="1.0">
                <var name="status" expr="'pending'"/>
                <eventprocessor statevariable="status">
                    <transition event="action" cond="event$.values.code == 200">
                        <assign name="status" expr="'approved'"/>
                    </transition>
                    <transition event="action" cond="event$.values.code != 200">
                        <assign name="status" expr="'declined'"/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlDocument doc = parser.parse(xml);
        CcxmlSession session = new CcxmlSession(doc);

        assertEquals("pending", session.getCurrentState());

        CcxmlEvent ev1 = CcxmlEvent.builder("action")
                .param("code", 200)
                .build();
        session.processEvent(ev1);

        assertEquals("approved", session.getCurrentState());
        assertEquals("approved", session.getVariable("status"));
    }

    @Test
    void testSendImmediateAndDelayed() throws Exception {
        String xml = """
            <ccxml version="1.0">
                <var name="triggered" expr="false"/>
                <var name="delayedTriggered" expr="false"/>
                <eventprocessor>
                    <transition event="trigger.send">
                        <send event="'internal.immediate'"/>
                        <send event="'internal.delayed'" delay="'50ms'" sendid="'myTimer'"/>
                    </transition>
                    <transition event="internal.immediate">
                        <assign name="triggered" expr="true"/>
                    </transition>
                    <transition event="internal.delayed">
                        <assign name="delayedTriggered" expr="true"/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlDocument doc = parser.parse(xml);
        CcxmlSession session = new CcxmlSession(doc);

        session.processEvent(new CcxmlEvent("trigger.send"));
        assertEquals(true, session.getVariable("triggered"));
        assertEquals(false, session.getVariable("delayedTriggered"));

        // Wait for delayed event to fire
        Thread.sleep(120);
        assertEquals(true, session.getVariable("delayedTriggered"));
    }

    @Test
    void testSendCancel() throws Exception {
        String xml = """
            <ccxml version="1.0">
                <var name="delayedFired" expr="false"/>
                <eventprocessor>
                    <transition event="schedule">
                        <send event="'cancel.target'" delay="'200ms'" sendid="'toCancel'"/>
                    </transition>
                    <transition event="cancel.now">
                        <cancel sendid="'toCancel'"/>
                    </transition>
                    <transition event="cancel.target">
                        <assign name="delayedFired" expr="true"/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlDocument doc = parser.parse(xml);
        CcxmlSession session = new CcxmlSession(doc);

        session.processEvent(new CcxmlEvent("schedule"));
        session.processEvent(new CcxmlEvent("cancel.now"));

        Thread.sleep(250);
        assertEquals(false, session.getVariable("delayedFired"));
    }
}
