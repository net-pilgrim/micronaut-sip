package net.pilgrim.vxml.speech;

import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

/**
 * Default ASR client fallback implementation.
 */
@Singleton
public class DefaultAsrClient implements AsrClient {

    @Override
    public Mono<String> recognize(byte[] pcmAudio, String grammar) {
        if (pcmAudio == null || pcmAudio.length == 0) {
            return Mono.just("");
        }
        return Mono.just("");
    }
}
