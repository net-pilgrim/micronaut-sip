package net.pilgrim.mrcp.vxml;

import net.pilgrim.mrcp.mistral.MockMistralAudioClient;
import net.pilgrim.vxml.ast.VxmlDocument;
import net.pilgrim.vxml.parser.VxmlParser;
import net.pilgrim.vxml.runtime.VxmlOutputSink;
import net.pilgrim.vxml.runtime.VxmlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class VoiceXmlSrgsMistralTest {

    private final VxmlParser parser = new VxmlParser();
    private MockMistralAudioClient mockMistral;
    private MrcpAsrClient asrClient;
    private MrcpTtsClient ttsClient;

    @BeforeEach
    void setUp() {
        mockMistral = new MockMistralAudioClient();
        asrClient = new MrcpAsrClient(mockMistral);
        ttsClient = new MrcpTtsClient(mockMistral);
    }

    @Test
    void testVxmlSrgsVoiceGrXmlRecognitionAndSemanticTagging() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <var name="selectedDept" expr="'none'"/>
                  <form id="menu">
                    <field name="dept">
                      <prompt>Say sales or support</prompt>
                      <grammar type="application/srgs+xml" mode="voice" root="departments">
                        <rule id="departments" scope="public">
                          <one-of>
                            <item>sales <tag>out="sales_queue";</tag></item>
                            <item>support <tag>out="support_queue";</tag></item>
                          </one-of>
                        </rule>
                      </grammar>
                      <filled>
                        <assign name="selectedDept" expr="dept"/>
                        <exit/>
                      </filled>
                    </field>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        AtomicBoolean completed = new AtomicBoolean(false);
        VxmlOutputSink sink = new StubOutputSink(() -> completed.set(true));

        VxmlSession session = VxmlSession.builder()
                .callId("vxml-srgs-test-1")
                .document(doc)
                .documentUri("inline:menu")
                .ttsClient(ttsClient)
                .asrClient(asrClient)
                .media(sink)
                .onDialogComplete(() -> completed.set(true))
                .build();

        session.start();

        assertEquals(VxmlSession.State.WAITING_FOR_INPUT, session.getState());
        assertFalse(completed.get());

        // Inbound speech utterance captured by ASR
        session.onSpeech("sales");

        assertTrue(completed.get());
        assertEquals("sales_queue", session.getDocumentScope().get("selectedDept"));
        assertEquals(VxmlSession.State.TERMINATED, session.getState());
    }

    @Test
    void testVxmlSrgsVoiceAudioFrameInputRecognitionViaMistral() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <var name="serviceType" expr="'none'"/>
                  <form id="orderForm">
                    <field name="service">
                      <prompt>Please say what you need</prompt>
                      <grammar type="application/srgs+xml" mode="voice" root="main">
                        <rule id="main" scope="public">
                          <one-of>
                            <item>operator <tag>out="agent";</tag></item>
                            <item>help <tag>out="faq";</tag></item>
                          </one-of>
                        </rule>
                      </grammar>
                      <filled>
                        <assign name="serviceType" expr="service"/>
                        <exit/>
                      </filled>
                    </field>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        AtomicBoolean completed = new AtomicBoolean(false);
        VxmlOutputSink sink = new StubOutputSink(() -> completed.set(true));

        // Tell mock Mistral client to map keyword operator to "operator"
        mockMistral.mapGrammarKeyword("operator", "operator");

        VxmlSession session = VxmlSession.builder()
                .callId("vxml-srgs-test-audio")
                .document(doc)
                .documentUri("inline:orderForm")
                .ttsClient(ttsClient)
                .asrClient(asrClient)
                .media(sink)
                .onDialogComplete(() -> completed.set(true))
                .build();

        session.start();
        assertEquals(VxmlSession.State.WAITING_FOR_INPUT, session.getState());

        // Feed raw PCM audio frames -> triggers asrClient.recognize() -> Mistral -> onSpeech("operator")
        session.onAudioInput(new byte[1600]);

        assertTrue(completed.get());
        assertEquals("agent", session.getDocumentScope().get("serviceType"));
        assertEquals(VxmlSession.State.TERMINATED, session.getState());
    }

    @Test
    void testVxmlSrgsAbnfGrammarSpeechRecognition() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <var name="drinkOrder" expr="'none'"/>
                  <form id="coffeeShop">
                    <field name="drink">
                      <prompt>What drink would you like?</prompt>
                      <grammar type="application/srgs">
                        #ABNF 1.0 UTF-8;
                        mode voice;
                        root $drinkRule;
                        $drinkRule = [i want a] (coffee {out="hot_coffee";} | tea {out="hot_tea";});
                      </grammar>
                      <filled>
                        <assign name="drinkOrder" expr="drink"/>
                        <exit/>
                      </filled>
                    </field>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        AtomicBoolean completed = new AtomicBoolean(false);
        VxmlOutputSink sink = new StubOutputSink(() -> completed.set(true));

        VxmlSession session = VxmlSession.builder()
                .callId("vxml-abnf-test")
                .document(doc)
                .documentUri("inline:coffeeShop")
                .ttsClient(ttsClient)
                .asrClient(asrClient)
                .media(sink)
                .onDialogComplete(() -> completed.set(true))
                .build();

        session.start();
        assertEquals(VxmlSession.State.WAITING_FOR_INPUT, session.getState());

        session.onSpeech("i want a coffee");

        assertTrue(completed.get());
        assertEquals("hot_coffee", session.getDocumentScope().get("drinkOrder"));
        assertEquals(VxmlSession.State.TERMINATED, session.getState());
    }

    @Test
    void testVxmlSrgsDtmfGrammarWithTagExtraction() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <var name="langSelected" expr="'unknown'"/>
                  <form id="langMenu">
                    <field name="lang">
                      <grammar type="application/srgs+xml" mode="dtmf" root="digits">
                        <rule id="digits">
                          <one-of>
                            <item>1 <tag>out="en-US";</tag></item>
                            <item>2 <tag>out="es-ES";</tag></item>
                          </one-of>
                        </rule>
                      </grammar>
                      <filled>
                        <assign name="langSelected" expr="lang"/>
                        <exit/>
                      </filled>
                    </field>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        AtomicBoolean completed = new AtomicBoolean(false);
        VxmlOutputSink sink = new StubOutputSink(() -> completed.set(true));

        VxmlSession session = VxmlSession.builder()
                .callId("vxml-dtmf-srgs-test")
                .document(doc)
                .documentUri("inline:langMenu")
                .media(sink)
                .onDialogComplete(() -> completed.set(true))
                .build();

        session.start();
        assertEquals(VxmlSession.State.WAITING_FOR_INPUT, session.getState());

        // Press '2'
        session.onDtmf('2');

        assertTrue(completed.get());
        assertEquals("es-ES", session.getDocumentScope().get("langSelected"));
        assertEquals(VxmlSession.State.TERMINATED, session.getState());
    }

    @Test
    void testVxmlSpeechNoMatchAndRecovery() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <var name="errorCount" expr="0"/>
                  <var name="finalAnswer" expr="'none'"/>
                  <form id="confirm">
                    <field name="ans">
                      <grammar type="application/srgs+xml" mode="voice" root="yesno">
                        <rule id="yesno">
                          <one-of>
                            <item>yes <tag>out="true";</tag></item>
                            <item>no <tag>out="false";</tag></item>
                          </one-of>
                        </rule>
                      </grammar>
                      <nomatch>
                        <assign name="errorCount" expr="errorCount + 1"/>
                      </nomatch>
                      <filled>
                        <assign name="finalAnswer" expr="ans"/>
                        <exit/>
                      </filled>
                    </field>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        AtomicBoolean completed = new AtomicBoolean(false);
        VxmlOutputSink sink = new StubOutputSink(() -> completed.set(true));

        VxmlSession session = VxmlSession.builder()
                .callId("vxml-nomatch-test")
                .document(doc)
                .documentUri("inline:confirm")
                .ttsClient(ttsClient)
                .asrClient(asrClient)
                .media(sink)
                .onDialogComplete(() -> completed.set(true))
                .build();

        session.start();
        assertEquals(VxmlSession.State.WAITING_FOR_INPUT, session.getState());

        // 1. Unrecognized utterance -> triggers nomatch
        session.onSpeech("banana");
        assertEquals(VxmlSession.State.WAITING_FOR_INPUT, session.getState());
        assertEquals(1, session.getDocumentScope().get("errorCount"));
        assertFalse(completed.get());

        // 2. Recognized utterance -> matches and fills
        session.onSpeech("yes");
        assertTrue(completed.get());
        assertEquals("true", session.getDocumentScope().get("finalAnswer"));
        assertEquals(VxmlSession.State.TERMINATED, session.getState());
    }

    @Test
    void testMultiTurnVoiceXmlDialogWithSrgsAndMistralAsr() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <var name="accountType" expr="'none'"/>
                  <var name="actionType" expr="'none'"/>
                  <form id="banking">
                    <field name="acc">
                      <prompt>Which account: checking or savings?</prompt>
                      <grammar type="application/srgs+xml" mode="voice" root="accounts">
                        <rule id="accounts">
                          <one-of>
                            <item>checking <tag>out="checking";</tag></item>
                            <item>savings <tag>out="savings";</tag></item>
                          </one-of>
                        </rule>
                      </grammar>
                      <filled>
                        <assign name="accountType" expr="acc"/>
                      </filled>
                    </field>

                    <field name="act">
                      <prompt>What would you like to do: balance or transfer?</prompt>
                      <grammar type="application/srgs+xml" mode="voice" root="actions">
                        <rule id="actions">
                          <one-of>
                            <item>balance <tag>out="inquire_balance";</tag></item>
                            <item>transfer <tag>out="do_transfer";</tag></item>
                          </one-of>
                        </rule>
                      </grammar>
                      <filled>
                        <assign name="actionType" expr="act"/>
                        <exit/>
                      </filled>
                    </field>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        AtomicBoolean completed = new AtomicBoolean(false);
        VxmlOutputSink sink = new StubOutputSink(() -> completed.set(true));

        VxmlSession session = VxmlSession.builder()
                .callId("vxml-multiturn-test")
                .document(doc)
                .documentUri("inline:banking")
                .ttsClient(ttsClient)
                .asrClient(asrClient)
                .media(sink)
                .onDialogComplete(() -> completed.set(true))
                .build();

        session.start();
        assertEquals(VxmlSession.State.WAITING_FOR_INPUT, session.getState());

        // Turn 1: Account
        session.onSpeech("savings");
        assertEquals("savings", session.getDocumentScope().get("accountType"));
        assertEquals(VxmlSession.State.WAITING_FOR_INPUT, session.getState());

        // Turn 2: Action
        session.onSpeech("balance");
        assertTrue(completed.get());
        assertEquals("inquire_balance", session.getDocumentScope().get("actionType"));
        assertEquals(VxmlSession.State.TERMINATED, session.getState());
    }

    private static class StubOutputSink implements VxmlOutputSink {
        private final Runnable onDialogComplete;

        StubOutputSink(Runnable onDialogComplete) {
            this.onDialogComplete = onDialogComplete;
        }

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
        public void onDialogComplete() {
            if (onDialogComplete != null) onDialogComplete.run();
        }
    }
}
