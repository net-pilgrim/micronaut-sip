package net.pilgrim.vxml.speech;

import reactor.core.publisher.Mono;

/**
 * Automated Speech Recognition service interface for VoiceXML speech input recognition.
 */
public interface AsrClient {

    /**
     * Recognizes speech input from captured 8000 Hz 16-bit mono PCM audio against an active grammar.
     *
     * @param pcmAudio captured PCM audio bytes
     * @param grammar  active grammar specification or rule name
     * @return Mono emitting recognized semantic utterance string, or empty if unrecognized
     */
    Mono<String> recognize(byte[] pcmAudio, String grammar);
}
