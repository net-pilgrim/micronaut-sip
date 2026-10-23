package net.pilgrim.mrcp.server;

import net.pilgrim.mrcp.client.MrcpClient;
import net.pilgrim.mrcp.message.*;
import net.pilgrim.mrcp.mistral.MockMistralAudioClient;
import net.pilgrim.mrcp.resource.MrcpChannelId;
import net.pilgrim.mrcp.resource.MrcpChannelManager;
import net.pilgrim.mrcp.resource.MrcpResourceType;
import net.pilgrim.mrcp.resource.SpeechRecognizerResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MrcpServerAsrIntegrationTest {

    private MrcpServer server;
    private MrcpClient client;
    private MrcpChannelManager channelManager;
    private MockMistralAudioClient mistralClient;
    private MrcpChannelId recogChannelId;
    private SpeechRecognizerResource recogResource;

    @BeforeEach
    void setup() {
        mistralClient = new MockMistralAudioClient();
        channelManager = new MrcpChannelManager(mistralClient);
        server = new MrcpServer(channelManager);
        server.start(0);

        recogChannelId = new MrcpChannelId("integration-recog", MrcpResourceType.SPEECHRECOG);
        recogResource = new SpeechRecognizerResource(recogChannelId, mistralClient);
        channelManager.registerResource(recogResource);

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
    void testEndToEndMrcpDefineGrammarAndRecognizeOverTcp() {
        assertTrue(server.isRunning());
        assertTrue(client.isConnected());

        // 1. Send DEFINE-GRAMMAR with SRGS XML
        long defReqId = client.nextRequestId();
        MrcpRequest defineReq = new MrcpRequest(MrcpMethod.DEFINE_GRAMMAR, defReqId);
        defineReq.getHeaders().setChannelIdentifier(recogChannelId);
        defineReq.getHeaders().setContentId("srgs-test-grammar");
        defineReq.setBody("""
                <grammar mode="voice" root="service">
                  <rule id="service">
                    <one-of>
                      <item>operator</item>
                      <item>support</item>
                    </one-of>
                  </rule>
                </grammar>
                """, "application/srgs+xml");

        StepVerifier.create(client.sendRequest(defineReq))
                .assertNext(resp -> {
                    assertEquals(defReqId, resp.getRequestId());
                    assertEquals(MrcpStatusCode.SUCCESS, resp.getStatusCode());
                    assertEquals(MrcpRequestState.COMPLETE, resp.getRequestState());
                })
                .verifyComplete();

        // 2. Send RECOGNIZE referencing the defined grammar
        long recogReqId = client.nextRequestId();
        MrcpRequest recogReq = new MrcpRequest(MrcpMethod.RECOGNIZE, recogReqId);
        recogReq.getHeaders().setChannelIdentifier(recogChannelId);
        recogReq.setBody("session:srgs-test-grammar", "text/uri-list");

        var eventMono = client.events(recogReqId)
                .filter(e -> e.getEvent() == MrcpEventName.RECOGNITION_COMPLETE)
                .next();

        StepVerifier.create(client.sendRequest(recogReq))
                .assertNext(resp -> {
                    assertEquals(recogReqId, resp.getRequestId());
                    assertEquals(MrcpStatusCode.IN_PROGRESS, resp.getStatusCode());
                    assertEquals(MrcpRequestState.IN_PROGRESS, resp.getRequestState());
                })
                .verifyComplete();

        // 3. Complete recognition on server
        recogResource.feedAudioBytes(new byte[1600]);
        recogResource.finishRecognition().subscribe();

        // 4. Client receives RECOGNITION-COMPLETE event with NLSML
        StepVerifier.create(eventMono.timeout(Duration.ofSeconds(3)))
                .assertNext(event -> {
                    assertEquals(recogReqId, event.getRequestId());
                    assertEquals(MrcpEventName.RECOGNITION_COMPLETE, event.getEvent());
                    assertEquals("000 success", event.getHeaders().getCompletionCause());
                    assertTrue(event.getBodyAsString().contains("<instance>operator</instance>"));
                })
                .verifyComplete();
    }
}
