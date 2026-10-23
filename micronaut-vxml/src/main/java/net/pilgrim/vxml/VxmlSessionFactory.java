package net.pilgrim.vxml;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.vxml.ast.VxmlDocument;
import net.pilgrim.vxml.loader.DefaultVxmlAudioLoader;
import net.pilgrim.vxml.loader.VxmlAudioLoader;
import net.pilgrim.vxml.loader.VxmlDocumentLoader;
import net.pilgrim.vxml.media.VxmlMedia;
import net.pilgrim.vxml.runtime.VxmlSession;
import net.pilgrim.vxml.speech.AsrClient;
import net.pilgrim.vxml.speech.DefaultAsrClient;
import net.pilgrim.vxml.speech.DefaultTtsClient;
import net.pilgrim.vxml.speech.TtsClient;

import java.util.UUID;

/**
 * Factory for creating VoiceXML interpreter sessions.
 */
@Singleton
public class VxmlSessionFactory {

    private final VxmlDocumentLoader documentLoader;
    private final VxmlAudioLoader audioLoader;
    private final TtsClient ttsClient;
    private final AsrClient asrClient;

    public VxmlSessionFactory() {
        this(new VxmlDocumentLoader(), new DefaultVxmlAudioLoader(), new DefaultTtsClient(), new DefaultAsrClient());
    }

    @Inject
    public VxmlSessionFactory(VxmlDocumentLoader documentLoader,
                              VxmlAudioLoader audioLoader,
                              @Nullable TtsClient ttsClient,
                              @Nullable AsrClient asrClient) {
        this.documentLoader = documentLoader != null ? documentLoader : new VxmlDocumentLoader();
        this.audioLoader = audioLoader != null ? audioLoader : new DefaultVxmlAudioLoader();
        this.ttsClient = ttsClient != null ? ttsClient : new DefaultTtsClient();
        this.asrClient = asrClient != null ? asrClient : new DefaultAsrClient();
    }

    public VxmlSession createSession(String documentUri, VxmlMedia media) {
        try {
            VxmlDocument doc = documentLoader.loadDocument(documentUri);
            if (doc == null) {
                throw new IllegalArgumentException("Failed to load VoiceXML document from: " + documentUri);
            }
            return VxmlSession.builder()
                    .callId(UUID.randomUUID().toString())
                    .document(doc)
                    .documentUri(documentUri)
                    .documentLoader(documentLoader)
                    .audioLoader(audioLoader)
                    .ttsClient(ttsClient)
                    .asrClient(asrClient)
                    .media(media)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load VoiceXML document: " + documentUri, e);
        }
    }
}
