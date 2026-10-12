package net.pilgrim.msml.engine;

import jakarta.inject.Singleton;
import net.pilgrim.msml.model.AudioMixConfig;
import net.pilgrim.msml.model.MsmlCommand;
import net.pilgrim.msml.model.StreamDirection;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;
import net.pilgrim.sip.rtp.media.AudioFrame;
import net.pilgrim.sip.rtp.media.AudioProcessor;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages RFC 5707 multi-party conference bridges and audio mixing across RTP media streams.
 */
@Singleton
public class MsmlConferenceManager {

    private static final Logger LOG = LoggerFactory.getLogger(MsmlConferenceManager.class);

    private final Map<String, ConferenceBridge> conferences = new ConcurrentHashMap<>();
    private final List<Consumer<MsmlEvent>> eventListeners = new CopyOnWriteArrayList<>();

    public void addEventListener(Consumer<MsmlEvent> listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }

    public void removeEventListener(Consumer<MsmlEvent> listener) {
        if (listener != null) {
            eventListeners.remove(listener);
        }
    }

    private void notifyEvent(MsmlEvent event) {
        for (Consumer<MsmlEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Throwable t) {
                LOG.warn("Error notifying conference event: {}", event.name(), t);
            }
        }
    }

    public MsmlResult createConference(MsmlCommand.CreateConference cmd) {
        String confId = cmd.target().id();
        if (conferences.containsKey(confId)) {
            LOG.warn("Conference {} already exists", confId);
            return MsmlResult.error(confId, 400, "Conference already exists: " + confId);
        }

        ConferenceBridge bridge = new ConferenceBridge(confId, cmd.audioMix(), cmd.deleteWhen(), this::onParticipantLeft);
        conferences.put(confId, bridge);
        LOG.info("Created MSML conference bridge: {}", confId);
        return MsmlResult.ok(confId);
    }

    public MsmlResult modifyConference(MsmlCommand.ModifyConference cmd) {
        String confId = cmd.target().id();
        ConferenceBridge bridge = conferences.get(confId);
        if (bridge == null) {
            return MsmlResult.error(confId, 404, "Conference not found: " + confId);
        }
        bridge.updateMixConfig(cmd.audioMix());
        LOG.info("Modified MSML conference bridge: {}", confId);
        return MsmlResult.ok(confId);
    }

    public MsmlResult destroyConference(MsmlCommand.DestroyConference cmd) {
        String confId = cmd.target().id();
        ConferenceBridge bridge = conferences.remove(confId);
        if (bridge == null) {
            return MsmlResult.error(confId, 404, "Conference not found: " + confId);
        }
        bridge.close();
        LOG.info("Destroyed MSML conference bridge: {}", confId);
        return MsmlResult.ok(confId);
    }

    public MsmlResult join(MsmlCommand.Join cmd, RtpMediaSession session) {
        // One ID is conference, the other is connection
        String confId = resolveConferenceId(cmd.id1().toString(), cmd.id2().toString());
        String callId = resolveConnectionId(cmd.id1().toString(), cmd.id2().toString());

        if (confId == null) {
            return MsmlResult.error(400, "Join target missing conference id (conf:...)");
        }
        if (session == null) {
            return MsmlResult.error(404, "Connection session not found for Call-ID: " + callId);
        }

        ConferenceBridge bridge = conferences.get(confId);
        if (bridge == null) {
            return MsmlResult.error(confId, 404, "Conference bridge not found: " + confId);
        }

        bridge.addParticipant(callId, session, cmd.direction());
        LOG.info("Joined Call-ID {} to conference {} with direction {}", callId, confId, cmd.direction());
        return MsmlResult.ok();
    }

    public MsmlResult unjoin(MsmlCommand.Unjoin cmd, RtpMediaSession session) {
        String confId = resolveConferenceId(cmd.id1().toString(), cmd.id2().toString());
        String callId = resolveConnectionId(cmd.id1().toString(), cmd.id2().toString());

        if (confId == null) {
            return MsmlResult.error(400, "Unjoin target missing conference id (conf:...)");
        }

        ConferenceBridge bridge = conferences.get(confId);
        if (bridge == null) {
            return MsmlResult.error(confId, 404, "Conference bridge not found: " + confId);
        }

        bridge.removeParticipant(callId);
        LOG.info("Unjoined Call-ID {} from conference {}", callId, confId);
        return MsmlResult.ok();
    }

    public boolean hasConference(String confId) {
        return conferences.containsKey(confId);
    }

    public Optional<ConferenceBridge> getConference(String confId) {
        return Optional.ofNullable(conferences.get(confId));
    }

    private void onParticipantLeft(ConferenceBridge bridge) {
        if ("nomedia".equalsIgnoreCase(bridge.deleteWhen()) && bridge.getParticipantCount() == 0) {
            LOG.info("Conference {} has no media remaining, triggering nomedia and cleanup", bridge.confId());
            conferences.remove(bridge.confId());
            bridge.close();
            notifyEvent(MsmlEvent.of(MsmlEvent.CONF_NOMEDIA, bridge.confId()));
        }
    }

    private static String resolveConferenceId(String id1, String id2) {
        if (id1.startsWith("conf:")) return id1.substring(5);
        if (id2.startsWith("conf:")) return id2.substring(5);
        return null;
    }

    private static String resolveConnectionId(String id1, String id2) {
        if (id1.startsWith("conn:")) return id1.substring(5);
        if (id2.startsWith("conn:")) return id2.substring(5);
        return id1.startsWith("conf:") ? id2 : id1;
    }

    // =========================================================================
    // Conference Bridge Inner Class
    // =========================================================================

    public static final class ConferenceBridge implements AutoCloseable {
        private final String confId;
        private volatile AudioMixConfig mixConfig;
        private final String deleteWhen;
        private final Consumer<ConferenceBridge> onEmptyCallback;
        private final Map<String, Participant> participants = new ConcurrentHashMap<>();

        public ConferenceBridge(String confId, AudioMixConfig mixConfig, String deleteWhen, Consumer<ConferenceBridge> onEmptyCallback) {
            this.confId = confId;
            this.mixConfig = mixConfig != null ? mixConfig : AudioMixConfig.standardPcm();
            this.deleteWhen = deleteWhen != null ? deleteWhen : "nomedia";
            this.onEmptyCallback = onEmptyCallback;
        }

        public String confId() {
            return confId;
        }

        public AudioMixConfig mixConfig() {
            return mixConfig;
        }

        public String deleteWhen() {
            return deleteWhen;
        }

        public void updateMixConfig(AudioMixConfig config) {
            if (config != null) {
                this.mixConfig = config;
            }
        }

        public int getParticipantCount() {
            return participants.size();
        }

        public synchronized void addParticipant(String callId, RtpMediaSession session, StreamDirection direction) {
            removeParticipant(callId);

            AudioProcessor processor = frame -> broadcastToOthers(callId, frame);
            if (direction == StreamDirection.SENDRECV || direction == StreamDirection.SENDONLY) {
                session.addInboundProcessor(processor);
            }

            participants.put(callId, new Participant(callId, session, direction, processor));
        }

        public synchronized void removeParticipant(String callId) {
            Participant removed = participants.remove(callId);
            if (removed != null) {
                removed.session().removeAudioProcessor(removed.processor());
                if (onEmptyCallback != null) {
                    onEmptyCallback.accept(this);
                }
            }
        }

        private void broadcastToOthers(String senderCallId, AudioFrame frame) {
            byte[] pcmData = frame.getPcm16Le();
            if (pcmData == null || pcmData.length == 0) return;

            for (Participant participant : participants.values()) {
                if (!participant.callId().equals(senderCallId) && participant.canReceive()) {
                    try {
                        participant.session().sendAudioFrame(pcmData, false).subscribe();
                    } catch (Throwable t) {
                        LOG.warn("Failed forwarding mixed audio frame to participant {}: {}", participant.callId(), t.getMessage());
                    }
                }
            }
        }

        @Override
        public synchronized void close() {
            for (Participant participant : participants.values()) {
                participant.session().removeAudioProcessor(participant.processor());
            }
            participants.clear();
        }
    }

    public record Participant(
            String callId,
            RtpMediaSession session,
            StreamDirection direction,
            AudioProcessor processor
    ) {
        public boolean canSend() {
            return direction == StreamDirection.SENDRECV || direction == StreamDirection.SENDONLY;
        }

        public boolean canReceive() {
            return direction == StreamDirection.SENDRECV || direction == StreamDirection.RECVONLY;
        }
    }
}
