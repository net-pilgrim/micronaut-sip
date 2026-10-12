package net.pilgrim.msml.engine;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.vxml.storage.MediaProtocolRegistry;

import java.net.URI;

/**
 * Loads audio content for MSML &lt;play&gt; sources by delegating to {@link MediaProtocolRegistry}.
 * Transparently supports all registered URI schemes ({@code prov://}, {@code tone:}, {@code tts:}, {@code file://}, {@code classpath:}, {@code http://}).
 */
@Singleton
public class MsmlAudioLoader {

    private final MediaProtocolRegistry protocolRegistry;

    public MsmlAudioLoader() {
        this(null);
    }

    @Inject
    public MsmlAudioLoader(@Nullable MediaProtocolRegistry protocolRegistry) {
        this.protocolRegistry = protocolRegistry != null ? protocolRegistry : new MediaProtocolRegistry();
    }

    public MediaProtocolRegistry getProtocolRegistry() {
        return protocolRegistry;
    }

    public byte[] loadAudio(URI uri) {
        if (uri == null) return new byte[0];
        return protocolRegistry.read(uri);
    }

    public byte[] loadAudio(String uriStr) {
        if (uriStr == null || uriStr.isBlank()) return new byte[0];
        return protocolRegistry.read(uriStr);
    }
}
