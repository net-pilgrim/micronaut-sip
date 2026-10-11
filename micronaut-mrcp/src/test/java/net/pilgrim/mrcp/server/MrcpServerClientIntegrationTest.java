package net.pilgrim.mrcp.server;

import net.pilgrim.mrcp.client.MrcpClient;
import net.pilgrim.mrcp.message.*;
import net.pilgrim.mrcp.mistral.MockMistralAudioClient;
import net.pilgrim.mrcp.resource.MrcpChannelId;
import net.pilgrim.mrcp.resource.MrcpChannelManager;
import net.pilgrim.mrcp.resource.MrcpResourceType;
import net.pilgrim.mrcp.resource.SpeechSynthesizerResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MrcpServerClientIntegrationTest {

    private MrcpServer server;
    private MrcpClient client;
    private MrcpChannelManager channelManager;
    private MockMistralAudioClient mistralClient;
    private MrcpChannelId synthChannelId;

    @BeforeEach
    void setup() {
        mistralClient = new MockMistralAudioClient();
        channelManager = new MrcpChannelManager(mistralClient);
        server = new MrcpServer(channelManager);
        server.start(0);

        synthChannelId = new MrcpChannelId("integration-synth", MrcpResourceType.SPEECHSYNTH);
        SpeechSynthesizerResource synthResource = new SpeechSynthesizerResource(synthChannelId, mistralClient);
        channelManager.registerResource(synthResource);

        client = new MrcpClient("127.0.0.1", server.getPort());
        client.connect();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void testEndToEndMrcpSpeakOverTcp() {
        assertTrue(server.isRunning());
        assertTrue(client.isConnected());

        long reqId = client.nextRequestId();
        MrcpRequest request = new MrcpRequest(MrcpMethod.SPEAK, reqId);
        request.getHeaders().setChannelIdentifier(synthChannelId);
        request.getHeaders().setVoiceName("mistral-en-1");
        request.setBody("Testing MRCPv2 TCP round-trip synthesis.", "text/plain");

        // Prepare event listener before sending request
        var eventMono = client.events(reqId)
                .filter(e -> e.getEvent() == MrcpEventName.SPEAK_COMPLETE)
                .next();

        // 1. Send request and verify 202 IN-PROGRESS response
        StepVerifier.create(client.sendRequest(request))
                .assertNext(resp -> {
                    assertEquals(reqId, resp.getRequestId());
                    assertEquals(MrcpStatusCode.IN_PROGRESS, resp.getStatusCode());
                    assertEquals(MrcpRequestState.IN_PROGRESS, resp.getRequestState());
                })
                .verifyComplete();

        // 2. Await asynchronous SPEAK-COMPLETE event
        StepVerifier.create(eventMono.timeout(Duration.ofSeconds(3)))
                .assertNext(event -> {
                    assertEquals(reqId, event.getRequestId());
                    assertEquals(MrcpEventName.SPEAK_COMPLETE, event.getEvent());
                    assertEquals(MrcpRequestState.COMPLETE, event.getRequestState());
                    assertEquals("000 normal", event.getHeaders().getCompletionCause());
                })
                .verifyComplete();
    }
}
