package net.pilgrim.vxml;

import net.pilgrim.vxml.ast.VxmlDocument;
import net.pilgrim.vxml.parser.VxmlParser;
import net.pilgrim.vxml.runtime.VxmlOutputSink;
import net.pilgrim.vxml.runtime.VxmlSession;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class VxmlSessionTest {

    private final VxmlParser parser = new VxmlParser();

    @Test
    void testBasicBlockExecutionAndTermination() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <var name="status" expr="'init'"/>
                  <form id="f1">
                    <block>
                      <assign name="status" expr="'completed'"/>
                      <exit/>
                    </block>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        AtomicBoolean dialogCompleted = new AtomicBoolean(false);

        VxmlOutputSink sink = new StubOutputSink(() -> dialogCompleted.set(true));

        VxmlSession session = new VxmlSession("call-test-1", doc, "inline:f1", null, null, null, sink);
        session.start();

        assertTrue(dialogCompleted.get());
        assertEquals("completed", session.getDocumentScope().get("status"));
        assertEquals(VxmlSession.State.TERMINATED, session.getState());
    }

    @Test
    void testMenuNavigationWithDtmf() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <var name="dest" expr="'none'"/>
                  <menu id="main">
                    <prompt>Press 1 for Sales, 2 for Support</prompt>
                    <choice dtmf="1" next="#sales"/>
                    <choice dtmf="2" next="#support"/>
                  </menu>
                  <form id="sales">
                    <block>
                      <assign name="dest" expr="'sales'"/>
                      <exit/>
                    </block>
                  </form>
                  <form id="support">
                    <block>
                      <assign name="dest" expr="'support'"/>
                      <exit/>
                    </block>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        AtomicBoolean completed = new AtomicBoolean(false);
        VxmlOutputSink sink = new StubOutputSink(() -> completed.set(true));

        VxmlSession session = new VxmlSession("call-test-2", doc, "inline:menu", null, null, null, sink);
        session.start();

        // While waiting for input, dialog is not terminated
        assertEquals(VxmlSession.State.WAITING_FOR_INPUT, session.getState());
        assertFalse(completed.get());

        // Send DTMF '1'
        session.onDtmf('1');

        // Should transition to sales form, assign dest='sales', and exit
        assertTrue(completed.get());
        assertEquals("sales", session.getDocumentScope().get("dest"));
        assertEquals(VxmlSession.State.TERMINATED, session.getState());
    }

    @Test
    void testFieldFilledActionAndConditionalBranching() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <var name="authResult" expr="'unknown'"/>
                  <form id="login">
                    <field name="pin" type="digits?length=4">
                      <prompt>Enter 4 digit pin</prompt>
                      <filled>
                        <if cond="pin == '4321'">
                          <assign name="authResult" expr="'success'"/>
                        <else/>
                          <assign name="authResult" expr="'failed'"/>
                        </if>
                        <exit/>
                      </filled>
                    </field>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        AtomicBoolean completed = new AtomicBoolean(false);
        VxmlOutputSink sink = new StubOutputSink(() -> completed.set(true));

        VxmlSession session = new VxmlSession("call-test-3", doc, "inline:login", null, null, null, sink);
        session.start();

        // Feed digits '4', '3', '2', '1'
        session.onDtmf('4');
        assertEquals(VxmlSession.State.WAITING_FOR_INPUT, session.getState());
        session.onDtmf('3');
        session.onDtmf('2');
        assertFalse(completed.get());
        session.onDtmf('1');

        // Completed 4 digits -> pin matched -> authResult = 'success'
        assertTrue(completed.get());
        assertEquals("success", session.getDocumentScope().get("authResult"));
        assertEquals(VxmlSession.State.TERMINATED, session.getState());
    }

    private static class StubOutputSink implements VxmlOutputSink {
        private final Runnable onDialogCompleteCb;
        private final AtomicBoolean playing = new AtomicBoolean(false);

        StubOutputSink(Runnable onDialogCompleteCb) {
            this.onDialogCompleteCb = onDialogCompleteCb;
        }

        @Override
        public void playAudio(byte[] pcmAudio, boolean bargeIn, Runnable onFinished) {
            playing.set(true);
            if (onFinished != null) onFinished.run();
            playing.set(false);
        }

        @Override
        public void stopAudio() {
            playing.set(false);
        }

        @Override
        public boolean isAudioPlaying() {
            return playing.get();
        }

        @Override
        public boolean isBargeInAllowed() {
            return true;
        }

        @Override
        public void onDialogComplete() {
            if (onDialogCompleteCb != null) {
                onDialogCompleteCb.run();
            }
        }
    }
}
