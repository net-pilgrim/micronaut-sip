package net.pilgrim.vxml.storage;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.vxml.speech.TtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Protocol handler for synthetic Text-to-Speech audio prompts ({@code tts:<prompt_text>}).
 */
@Singleton
public class TtsProtocolHandler implements MediaProtocolHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TtsProtocolHandler.class);

    private final TtsClient ttsClient;

    public TtsProtocolHandler() {
        this(null);
    }

    @Inject
    public TtsProtocolHandler(@Nullable TtsClient ttsClient) {
        this.ttsClient = ttsClient;
    }

    @Override
    public String getScheme() {
        return "tts";
    }

    @Override
    public byte[] read(URI uri) throws IOException {
        Objects.requireNonNull(uri, "uri must not be null");
        String uriStr = uri.toString();
        String text = uriStr.startsWith("tts:") ? uriStr.substring(4).trim() : uriStr;

        if (ttsClient != null) {
            try {
                byte[] audio = ttsClient.synthesize(text, null, null).block(Duration.ofSeconds(5));
                return audio != null ? audio : new byte[0];
            } catch (Exception e) {
                LOG.warn("Failed synthesizing TTS text: {}", text, e);
            }
        } else {
            LOG.warn("No TtsClient available to synthesize: {}", text);
        }
        return new byte[0];
    }
}
