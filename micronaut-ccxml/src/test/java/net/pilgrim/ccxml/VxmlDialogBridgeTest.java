package net.pilgrim.ccxml;

import net.pilgrim.ccxml.ast.CcxmlDocument;
import net.pilgrim.ccxml.bridge.InMemoryConnectionBridge;
import net.pilgrim.ccxml.bridge.InMemoryMediaBridge;
import net.pilgrim.ccxml.bridge.VxmlDialogBridge;
import net.pilgrim.ccxml.parser.CcxmlParser;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VxmlDialogBridgeTest {

    @Test
    void testVxmlDialogBridgeExecutionWithCcxml() {
        String vxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <vxml version="2.1" xmlns="http://www.w3.org/2001/vxml">
                <form id="greeting">
                    <block>
                        <assign name="dialogResult" expr="'verified'"/>
                        <exit expr="'done'"/>
                    </block>
                </form>
            </vxml>
            """;

        String ccxml = """
            <ccxml version="1.0">
                <var name="vxmlResult"/>
                <eventprocessor statevariable="state">
                    <transition event="start.dialog" state="init">
                        <dialogstart src="vxmlContent" dialogid="dlgId"/>
                        <assign name="state" expr="'waiting_vxml'"/>
                    </transition>
                    <transition event="dialog.exit" state="waiting_vxml">
                        <assign name="vxmlResult" expr="event$.values.dialogResult"/>
                        <assign name="state" expr="'vxml_finished'"/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlParser parser = new CcxmlParser();
        CcxmlDocument doc = parser.parse(ccxml);

        VxmlDialogBridge vxmlBridge = new VxmlDialogBridge();
        vxmlBridge.prepareDialog("vxmlContent", vxml, "application/voicexml+xml").block();

        CcxmlSession ccxmlSession = new CcxmlSession(
                "session-vxml-test",
                doc,
                new InMemoryConnectionBridge(),
                vxmlBridge,
                new InMemoryMediaBridge()
        );
        ccxmlSession.setVariable("vxmlContent", "vxmlContent");

        vxmlBridge.setEventSink(ccxmlSession::processEvent);

        // Start the dialog
        ccxmlSession.processEvent(new CcxmlEvent("start.dialog", "conn_test_100"));

        // Wait for async VoiceXML execution to finish and deliver dialog.exit
        long deadline = System.currentTimeMillis() + 2000;
        while (!"vxml_finished".equals(ccxmlSession.getCurrentState()) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {}
        }

        // Verify state changed after VoiceXML dialog executed and returned
        assertEquals("vxml_finished", ccxmlSession.getCurrentState());
        assertEquals("verified", ccxmlSession.getVariable("vxmlResult"));
    }
}
