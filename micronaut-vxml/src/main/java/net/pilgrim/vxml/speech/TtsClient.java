package net.pilgrim.vxml.speech;

import reactor.core.publisher.Mono;

/**
 * Text-to-Speech synthesis service interface for VoiceXML prompts.
 */
public interface TtsClient {

    /**
     * Synthesizes text or SSML into 8000 Hz 16-bit mono signed PCM-16LE audio bytes.
     *
     * @param text   text or SSML string to synthesize
     * @param voice  optional voice name or null for default
     * @param locale optional language/locale tag (e.g. "en-US") or null
     * @return Mono emitting PCM-16LE audio bytes
     */
    Mono<byte[]> synthesize(String text, String voice, String locale);
}
