package net.pilgrim.mrcp.resource;

import net.pilgrim.mrcp.message.*;
import net.pilgrim.mrcp.mistral.MockMistralAudioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MrcpSpeechRecognizerResourceTest {

    private MrcpChannelId channelId;
    private MockMistralAudioClient mockMistral;
    private SpeechRecognizerResource recognizer;

    @BeforeEach
    void setUp() {
        channelId = new MrcpChannelId("test-asr-channel", MrcpResourceType.SPEECHRECOG);
        mockMistral = new MockMistralAudioClient();
        recognizer = new SpeechRecognizerResource(channelId, mockMistral);
    }

    @Test
    void testDefineGrammarAndRecognizeWithReference() {
        List<MrcpEvent> events = new ArrayList<>();

        // 1. Send DEFINE-GRAMMAR
        MrcpRequest defineReq = new MrcpRequest(MrcpMethod.DEFINE_GRAMMAR, 100);
        defineReq.getHeaders().setChannelIdentifier(channelId);
        defineReq.getHeaders().setContentId("grammar1@domain.com");
        defineReq.getHeaders().setContentType("application/srgs+xml");
        defineReq.setBody("""
                <grammar mode="voice" root="dept">
                  <rule id="dept">
                    <one-of>
                      <item>sales</item>
                      <item>support</item>
                    </one-of>
                  </rule>
                </grammar>
                """, "application/srgs+xml");

        StepVerifier.create(recognizer.handleRequest(defineReq, events::add))
                .assertNext(resp -> {
                    assertEquals(100, resp.getRequestId());
                    assertEquals(MrcpStatusCode.SUCCESS, resp.getStatusCode());
                    assertEquals(MrcpRequestState.COMPLETE, resp.getRequestState());
                })
                .verifyComplete();

        // 2. Send RECOGNIZE referencing session:grammar1@domain.com
        MrcpRequest recogReq = new MrcpRequest(MrcpMethod.RECOGNIZE, 101);
        recogReq.getHeaders().setChannelIdentifier(channelId);
        recogReq.setBody("session:grammar1@domain.com", "text/uri-list");

        StepVerifier.create(recognizer.handleRequest(recogReq, events::add))
                .assertNext(resp -> {
                    assertEquals(101, resp.getRequestId());
                    assertEquals(MrcpStatusCode.IN_PROGRESS, resp.getStatusCode());
                    assertEquals(MrcpRequestState.IN_PROGRESS, resp.getRequestState());
                })
                .verifyComplete();

        // 3. Feed audio bytes -> fires START-OF-INPUT event
        recognizer.feedAudioBytes(new byte[1600]);
        assertEquals(1, events.size());
        assertEquals(MrcpEventName.START_OF_INPUT, events.get(0).getEvent());
        assertEquals(101, events.get(0).getRequestId());

        // 4. Complete recognition -> fires RECOGNITION-COMPLETE event with NLSML
        StepVerifier.create(recognizer.finishRecognition())
                .assertNext(result -> {
                    assertNotNull(result);
                    assertEquals("sales", result.getText());
                })
                .verifyComplete();

        assertEquals(2, events.size());
        MrcpEvent completeEvent = events.get(1);
        assertEquals(MrcpEventName.RECOGNITION_COMPLETE, completeEvent.getEvent());
        assertEquals(101, completeEvent.getRequestId());
        assertEquals("000 success", completeEvent.getHeaders().getCompletionCause());
        assertNotNull(completeEvent.getBodyAsString());
        assertTrue(completeEvent.getBodyAsString().contains("<instance>sales</instance>"));
    }

    @Test
    void testInterpretMethod() {
        MrcpRequest req = new MrcpRequest(MrcpMethod.INTERPRET, 102);
        req.getHeaders().setChannelIdentifier(channelId);
        req.setBody("yes", "text/plain");

        StepVerifier.create(recognizer.handleRequest(req, e -> {}))
                .assertNext(resp -> {
                    assertEquals(102, resp.getRequestId());
                    assertEquals(MrcpStatusCode.SUCCESS, resp.getStatusCode());
                    assertEquals("application/nlsml+xml", resp.getHeaders().getContentType());
                    assertTrue(resp.getBodyAsString().contains("<instance>yes</instance>"));
                })
                .verifyComplete();
    }
}
