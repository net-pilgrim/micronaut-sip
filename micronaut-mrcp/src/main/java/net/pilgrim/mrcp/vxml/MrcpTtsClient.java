package net.pilgrim.mrcp.vxml;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import net.pilgrim.mrcp.mistral.MistralAudioClient;
import net.pilgrim.vxml.speech.TtsClient;
import reactor.core.publisher.Mono;

/**
 * VoiceXML {@link TtsClient} adapter bridging VoiceXML prompt synthesis to Mistral Audio / MRCP.
 */
@Singleton
@Secondary
@Named("mrcp")
@Requires(classes = TtsClient.class)
public class MrcpTtsClient implements TtsClient {

    private final MistralAudioClient mistralClient;

    @Inject
    public MrcpTtsClient(MistralAudioClient mistralClient) {
        this.mistralClient = mistralClient;
    }

    @Override
    public Mono<byte[]> synthesize(String text, String voice, String locale) {
        return mistralClient.synthesize(text, voice, locale);
    }
}
