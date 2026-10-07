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

    @Test
    void testDecoupledVxmlMediaInjection() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <form id="f1">
                    <block>
                      <prompt>Welcome to test</prompt>
                      <exit/>
                    </block>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        AtomicBoolean dialogCompleted = new AtomicBoolean(false);
        AtomicInteger audioPlayCount = new AtomicInteger(0);

        net.pilgrim.vxml.media.VxmlMedia mockMedia = new net.pilgrim.vxml.media.VxmlMedia() {
            private final AtomicBoolean playing = new AtomicBoolean(false);

            @Override
            public void playAudio(byte[] pcmAudio, boolean bargeIn, Runnable onFinished) {
                audioPlayCount.incrementAndGet();
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
        };

        net.pilgrim.vxml.runtime.VxmlInterpreter interpreter = VxmlSession.builder()
                .callId("call-media-inject-1")
                .document(doc)
                .documentUri("inline:f1")
                .media(mockMedia)
                .onDialogComplete(() -> dialogCompleted.set(true))
                .build();

        assertSame(mockMedia, interpreter.getMedia());
        interpreter.start();

        assertTrue(dialogCompleted.get());
        assertTrue(audioPlayCount.get() > 0);
        assertEquals(VxmlSession.State.TERMINATED, interpreter.getState());
    }

    @Test
    void testSetMediaRuntimeInjection() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <form id="f1">
                    <field name="choice" type="digits?length=1">
                      <prompt>Please press 1</prompt>
                      <filled>
                        <exit/>
                      </filled>
                    </field>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        AtomicBoolean dialogCompleted = new AtomicBoolean(false);
        AtomicBoolean stopCalled = new AtomicBoolean(false);

        VxmlSession session = new VxmlSession("call-media-set-1", doc, "inline:f1", null, null, null,
                (net.pilgrim.vxml.media.VxmlMedia) null, () -> dialogCompleted.set(true));

        assertNotNull(session.getMedia()); // defaults to NOOP

        net.pilgrim.vxml.media.VxmlMedia newMedia = new net.pilgrim.vxml.media.VxmlMedia() {
            private volatile boolean playing = false;

            @Override
            public void playAudio(byte[] pcmAudio, boolean bargeIn, Runnable onFinished) {
                playing = true;
            }

            @Override
            public void stopAudio() {
                playing = false;
                stopCalled.set(true);
            }

            @Override
            public boolean isAudioPlaying() {
                return playing;
            }

            @Override
            public boolean isBargeInAllowed() {
                return true;
            }
        };

        session.setMedia(newMedia);
        assertSame(newMedia, session.getMedia());

        session.start();
        assertEquals(VxmlSession.State.WAITING_FOR_INPUT, session.getState());
        assertTrue(newMedia.isAudioPlaying());

        // Send DTMF '1' which should trigger barge-in stopAudio() and fill field
        session.onDtmf('1');

        assertTrue(stopCalled.get());
        assertTrue(dialogCompleted.get());
        assertEquals(VxmlSession.State.TERMINATED, session.getState());
    }

    @Test
    void testRecordFormItemExecutionAndDtmfTermination() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <var name="status" expr="'not_recorded'"/>
                  <form id="recordTest">
                    <record name="msg" beep="true" maxtime="10s" dtmfterm="true">
                      <prompt bargein="true">Please record after the tone</prompt>
                      <filled>
                        <assign name="status" expr="'recorded'"/>
                        <exit/>
                      </filled>
                    </record>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        AtomicBoolean recordingStarted = new AtomicBoolean(false);
        AtomicBoolean recordingStopped = new AtomicBoolean(false);
        AtomicBoolean dialogCompleted = new AtomicBoolean(false);

        net.pilgrim.vxml.media.VxmlMedia media = new net.pilgrim.vxml.media.VxmlMedia() {
            private java.util.function.Consumer<Character> dtmfListener;
            private boolean recording = false;

            @Override
            public void playAudio(byte[] pcmAudio, boolean bargeIn, Runnable onFinished) {
                if (onFinished != null) onFinished.run();
            }

            @Override
            public void stopAudio() {}

            @Override
            public boolean isAudioPlaying() { return false; }

            @Override
            public boolean isBargeInAllowed() { return true; }

            @Override
            public void setDtmfListener(java.util.function.Consumer<Character> listener) {
                this.dtmfListener = listener;
            }

            @Override
            public void startRecording() {
                recording = true;
                recordingStarted.set(true);
            }

            @Override
            public byte[] stopRecording() {
                recording = false;
                recordingStopped.set(true);
                return new byte[1600]; // simulate 100ms recorded audio
            }

            @Override
            public boolean isRecording() { return recording; }
        };

        VxmlSession session = VxmlSession.builder()
                .callId("call-record-test")
                .document(doc)
                .documentUri("inline:recordTest")
                .media(media)
                .onDialogComplete(() -> dialogCompleted.set(true))
                .build();

        session.start();

        // While in <record>, recording should be active
        assertEquals(VxmlSession.State.RECORDING, session.getState());
        assertTrue(recordingStarted.get());

        // DTMF '#' terminates recording
        session.onDtmf('#');

        // Dialog should transition into <filled>, assign status='recorded', and exit
        assertTrue(recordingStopped.get());
        assertTrue(dialogCompleted.get());
        assertEquals("recorded", session.getDocumentScope().get("status"));
        assertEquals(1600, ((byte[]) session.getDialogScope().get("msg")).length);
        assertEquals("#", session.getDialogScope().get("msg$.termchar"));
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
