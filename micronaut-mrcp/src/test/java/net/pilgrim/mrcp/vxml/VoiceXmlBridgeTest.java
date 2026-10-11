package net.pilgrim.mrcp.vxml;

import net.pilgrim.mrcp.mistral.MockMistralAudioClient;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

class VoiceXmlBridgeTest {

    @Test
    void testVoiceXmlTtsBridge() {
        MockMistralAudioClient mockClient = new MockMistralAudioClient();
        MrcpTtsClient ttsBridge = new MrcpTtsClient(mockClient);

        StepVerifier.create(ttsBridge.synthesize("Please say your account number.", "mistral-en-1", "en-US"))
                .assertNext(pcm -> {
                    assertNotNull(pcm);
                    assertTrue(pcm.length > 0);
                })
                .verifyComplete();
    }

    @Test
    void testVoiceXmlAsrBridge() {
        MockMistralAudioClient mockClient = new MockMistralAudioClient();
        mockClient.mapGrammarKeyword("account", "account inquiry");

        MrcpAsrClient asrBridge = new MrcpAsrClient(mockClient);

        StepVerifier.create(asrBridge.recognize(new byte[1600], "builtin:grammar/account"))
                .assertNext(utterance -> {
                    assertEquals("account inquiry", utterance);
                })
                .verifyComplete();
    }
}
