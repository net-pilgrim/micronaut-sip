package net.pilgrim.webrtc;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.mrcp.mistral.MistralAudioClient;
import net.pilgrim.vxml.speech.TtsClient;
import reactor.core.publisher.Mono;

@Singleton
public class MistralTtsAdapter implements TtsClient {

    private final MistralAudioClient mistralClient;

    @Inject
    public MistralTtsAdapter(MistralAudioClient mistralClient) {
        this.mistralClient = mistralClient;
    }

    @Override
    public Mono<byte[]> synthesize(String text, @Nullable String voice, @Nullable String locale) {
        return mistralClient.synthesize(text, voice, locale);
    }
}
