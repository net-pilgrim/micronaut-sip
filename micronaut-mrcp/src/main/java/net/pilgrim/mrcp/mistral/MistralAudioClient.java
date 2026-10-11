package net.pilgrim.mrcp.mistral;

import io.micronaut.core.annotation.Nullable;
import reactor.core.publisher.Mono;

/**
 * Client interface for interacting with Mistral Audio speech models (ASR and TTS).
 */
public interface MistralAudioClient {

    /**
     * Synthesizes plain text or SSML into 8000 Hz 16-bit mono signed PCM-16LE audio bytes.
     *
     * @param text     text or SSML string to synthesize
     * @param voice    voice profile or name (e.g. "mistral-en-1") or null for default
     * @param language speech language code (e.g. "en-US") or null
     * @return Mono emitting raw PCM-16LE audio bytes
     */
    Mono<byte[]> synthesize(String text, @Nullable String voice, @Nullable String language);

    /**
     * Recognizes speech from captured 8000 Hz 16-bit mono signed PCM-16LE audio bytes.
     *
     * @param pcmAudio captured PCM audio bytes
     * @param grammar  optional grammar rule or hint or null
     * @param language speech language code (e.g. "en-US") or null
     * @return Mono emitting recognition result with transcription, confidence, and NLSML
     */
    Mono<MistralRecognitionResult> recognize(byte[] pcmAudio, @Nullable String grammar, @Nullable String language);
}
