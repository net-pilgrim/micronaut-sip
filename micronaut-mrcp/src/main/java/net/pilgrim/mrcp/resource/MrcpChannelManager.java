package net.pilgrim.mrcp.resource;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.mrcp.mistral.MistralAudioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages active MRCP resource channels and their life cycle.
 */
@Singleton
public class MrcpChannelManager {

    private static final Logger LOG = LoggerFactory.getLogger(MrcpChannelManager.class);

    private final ConcurrentMap<MrcpChannelId, MrcpResource> activeResources = new ConcurrentHashMap<>();
    private final MistralAudioClient mistralClient;

    @Inject
    public MrcpChannelManager(MistralAudioClient mistralClient) {
        this.mistralClient = mistralClient;
    }

    public void registerResource(MrcpResource resource) {
        if (resource != null) {
            activeResources.put(resource.getChannelId(), resource);
            LOG.debug("Registered MRCP resource channel: {}", resource.getChannelId());
        }
    }

    public MrcpResource unregisterResource(MrcpChannelId channelId) {
        if (channelId == null) return null;
        MrcpResource removed = activeResources.remove(channelId);
        if (removed != null) {
            removed.close();
            LOG.debug("Unregistered MRCP resource channel: {}", channelId);
        }
        return removed;
    }

    public MrcpResource getResource(MrcpChannelId channelId) {
        if (channelId == null) return null;
        return activeResources.get(channelId);
    }

    public MrcpResource getOrCreateResource(MrcpChannelId channelId) {
        if (channelId == null) return null;
        return activeResources.computeIfAbsent(channelId, id -> {
            if (id.getResourceType() == MrcpResourceType.SPEECHSYNTH) {
                LOG.info("Provisioned MRCP SpeechSynthesizer resource for channel: {}", id);
                return new SpeechSynthesizerResource(id, mistralClient);
            } else if (id.getResourceType() == MrcpResourceType.SPEECHRECOG) {
                LOG.info("Provisioned MRCP SpeechRecognizer resource for channel: {}", id);
                return new SpeechRecognizerResource(id, mistralClient);
            }
            return null;
        });
    }

    public Collection<MrcpResource> getActiveResources() {
        return Collections.unmodifiableCollection(activeResources.values());
    }

    public SpeechSynthesizerResource createSynthesizer() {
        MrcpChannelId id = MrcpChannelId.generate(MrcpResourceType.SPEECHSYNTH);
        SpeechSynthesizerResource synth = new SpeechSynthesizerResource(id, mistralClient);
        registerResource(synth);
        return synth;
    }

    public SpeechRecognizerResource createRecognizer() {
        MrcpChannelId id = MrcpChannelId.generate(MrcpResourceType.SPEECHRECOG);
        SpeechRecognizerResource recog = new SpeechRecognizerResource(id, mistralClient);
        registerResource(recog);
        return recog;
    }

    public void closeAll() {
        for (MrcpResource resource : activeResources.values()) {
            try {
                resource.close();
            } catch (Exception ignored) {}
        }
        activeResources.clear();
    }
}
