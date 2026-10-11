package net.pilgrim.mrcp.vxml;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import net.pilgrim.mrcp.mistral.MistralAudioClient;
import net.pilgrim.mrcp.mistral.MistralRecognitionResult;
import net.pilgrim.vxml.speech.AsrClient;
import reactor.core.publisher.Mono;

/**
 * VoiceXML {@link AsrClient} adapter bridging VoiceXML field speech input recognition to Mistral Audio / MRCP.
 */
@Singleton
@Secondary
@Named("mrcp")
@Requires(classes = AsrClient.class)
public class MrcpAsrClient implements AsrClient {

    private final MistralAudioClient mistralClient;

    @Inject
    public MrcpAsrClient(MistralAudioClient mistralClient) {
        this.mistralClient = mistralClient;
    }

    @Override
    public Mono<String> recognize(byte[] pcmAudio, String grammar) {
        return mistralClient.recognize(pcmAudio, grammar, null)
                .map(MistralRecognitionResult::getText);
    }
}
