package net.pilgrim.vxml.loader;

import io.micronaut.context.annotation.Secondary;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.vxml.storage.MediaProtocolRegistry;

/**
 * Default implementation of {@link VxmlAudioLoader} delegating to {@link MediaProtocolRegistry}.
 * Natively supports {@code prov://}, {@code tone:}, {@code classpath:}, {@code http://}, and bounded {@code file://}.
 */
@Singleton
@Secondary
public class DefaultVxmlAudioLoader implements VxmlAudioLoader {

    private final MediaProtocolRegistry protocolRegistry;

    public DefaultVxmlAudioLoader() {
        this(null);
    }

    @Inject
    public DefaultVxmlAudioLoader(@Nullable MediaProtocolRegistry protocolRegistry) {
        this.protocolRegistry = protocolRegistry != null ? protocolRegistry : new MediaProtocolRegistry();
    }

    @Override
    public byte[] loadAudio(String uri) {
        return protocolRegistry.read(uri);
    }
}
