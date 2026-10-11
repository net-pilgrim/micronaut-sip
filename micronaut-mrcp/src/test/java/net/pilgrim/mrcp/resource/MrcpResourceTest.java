package net.pilgrim.mrcp.resource;

import net.pilgrim.mrcp.message.*;
import net.pilgrim.mrcp.mistral.MockMistralAudioClient;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MrcpResourceTest {

    @Test
    void testSpeechSynthesizerResourceSpeakAndComplete() throws InterruptedException {
        MockMistralAudioClient mistralClient = new MockMistralAudioClient();
        MrcpChannelId channelId = new MrcpChannelId("synth-ch-1", MrcpResourceType.SPEECHSYNTH);
        SpeechSynthesizerResource synth = new SpeechSynthesizerResource(channelId, mistralClient);

        MrcpRequest speakReq = new MrcpRequest(MrcpMethod.SPEAK, 101);
        speakReq.getHeaders().setChannelIdentifier(channelId);
        speakReq.setBody("Hello from Mistral MRCP", "text/plain");

        CopyOnWriteArrayList<MrcpEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        StepVerifier.create(synth.handleRequest(speakReq, event -> {
                    events.add(event);
                    latch.countDown();
                }))
                .assertNext(resp -> {
                    assertEquals(101, resp.getRequestId());
                    assertEquals(MrcpStatusCode.IN_PROGRESS, resp.getStatusCode());
                    assertEquals(MrcpRequestState.IN_PROGRESS, resp.getRequestState());
                })
                .verifyComplete();

        assertTrue(latch.await(3, TimeUnit.SECONDS), "SPEAK-COMPLETE event should be emitted");
        assertEquals(1, events.size());
        MrcpEvent completeEvent = events.getFirst();
        assertEquals(MrcpEventName.SPEAK_COMPLETE, completeEvent.getEvent());
        assertEquals(101, completeEvent.getRequestId());
        assertEquals("000 normal", completeEvent.getHeaders().getCompletionCause());
        assertNotNull(synth.getLastSynthesizedAudio());
    }

    @Test
    void testSpeechRecognizerResourceRecognizeAndComplete() {
        MockMistralAudioClient mistralClient = new MockMistralAudioClient();
        mistralClient.mapGrammarKeyword("support", "customer support");

        MrcpChannelId channelId = new MrcpChannelId("recog-ch-1", MrcpResourceType.SPEECHRECOG);
        SpeechRecognizerResource recog = new SpeechRecognizerResource(channelId, mistralClient);

        MrcpRequest recogReq = new MrcpRequest(MrcpMethod.RECOGNIZE, 202);
        recogReq.getHeaders().setChannelIdentifier(channelId);
        recogReq.setBody("grammar: support | sales", "text/plain");

        CopyOnWriteArrayList<MrcpEvent> events = new CopyOnWriteArrayList<>();

        // 1. RECOGNIZE -> 202 IN-PROGRESS
        StepVerifier.create(recog.handleRequest(recogReq, events::add))
                .assertNext(resp -> {
                    assertEquals(202, resp.getRequestId());
                    assertEquals(MrcpStatusCode.IN_PROGRESS, resp.getStatusCode());
                })
                .verifyComplete();

        // 2. Feed audio -> START-OF-INPUT event
        recog.feedAudioBytes(new byte[1600]);
        assertEquals(1, events.size());
        assertEquals(MrcpEventName.START_OF_INPUT, events.getFirst().getEvent());

        // 3. Finish recognition -> RECOGNITION-COMPLETE event with NLSML
        StepVerifier.create(recog.finishRecognition())
                .assertNext(result -> {
                    assertEquals("customer support", result.getText());
                })
                .verifyComplete();

        assertEquals(2, events.size());
        MrcpEvent completeEvent = events.get(1);
        assertEquals(MrcpEventName.RECOGNITION_COMPLETE, completeEvent.getEvent());
        assertEquals(202, completeEvent.getRequestId());
        assertTrue(completeEvent.getBodyAsString().contains("<instance>customer support</instance>"));
    }
}
