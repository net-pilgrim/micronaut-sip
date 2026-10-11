package net.pilgrim.mrcp.mistral;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

class MistralAudioClientTest {

    @Test
    void testMockSynthesizerProducesPcmFrames() {
        MockMistralAudioClient client = new MockMistralAudioClient();

        StepVerifier.create(client.synthesize("Welcome to our automated voice portal.", "mistral-en-1", "en-US"))
                .assertNext(pcmBytes -> {
                    assertNotNull(pcmBytes);
                    assertTrue(pcmBytes.length > 0);
                    // Must be even number of bytes for 16-bit PCM
                    assertEquals(0, pcmBytes.length % 2);
                    // 6 words: ~900ms = ~7200 samples = ~14400 bytes
                    assertTrue(pcmBytes.length >= 6400, "Audio bytes length should match estimated word duration");
                })
                .verifyComplete();
    }

    @Test
    void testMockRecognizerKeywordMatchingAndNlsml() {
        MockMistralAudioClient client = new MockMistralAudioClient();
        client.mapGrammarKeyword("support", "technical support");

        byte[] fakePcm = new byte[3200]; // 200 ms of audio

        StepVerifier.create(client.recognize(fakePcm, "option(sales | support | billing)", "en-US"))
                .assertNext(result -> {
                    assertNotNull(result);
                    assertEquals("technical support", result.getText());
                    assertTrue(result.getConfidence() >= 0.9f);

                    String nlsml = result.toNlsml();
                    assertTrue(nlsml.contains("<instance>technical support</instance>"));
                    assertTrue(nlsml.contains("<input mode=\"speech\">technical support</input>"));
                })
                .verifyComplete();
    }

    @Test
    void testPcmToWavEncoding() {
        byte[] pcm = new byte[1600]; // 100 ms of 8 kHz 16-bit mono
        byte[] wav = DefaultMistralAudioClient.encodePcmToWav(pcm, 8000, 1);

        assertEquals(44 + pcm.length, wav.length);
        assertEquals('R', (char) wav[0]);
        assertEquals('I', (char) wav[1]);
        assertEquals('F', (char) wav[2]);
        assertEquals('F', (char) wav[3]);
        assertEquals('W', (char) wav[8]);
        assertEquals('A', (char) wav[9]);
        assertEquals('V', (char) wav[10]);
        assertEquals('E', (char) wav[11]);
    }
}
