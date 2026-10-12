package net.pilgrim.msml.model;

import net.pilgrim.msml.model.moml.MomlDialog;

import java.util.Objects;
import java.util.Optional;

/**
 * Sealed interface representing top-level MSML control commands per RFC 5707.
 */
public sealed interface MsmlCommand permits
        MsmlCommand.DialogStart,
        MsmlCommand.DialogEnd,
        MsmlCommand.CreateConference,
        MsmlCommand.ModifyConference,
        MsmlCommand.DestroyConference,
        MsmlCommand.Join,
        MsmlCommand.Unjoin,
        MsmlCommand.Audit {

    /**
     * Starts an interactive media dialog on a connection or conference (RFC 5707 Section 3.1).
     * Supports both MOML dialogs and delegated VoiceXML execution.
     */
    record DialogStart(
            String id,
            MsmlTarget target,
            String type,
            MomlDialog momlDialog,
            String vxmlUrl,
            String inlineVxml
    ) implements MsmlCommand {
        public DialogStart {
            Objects.requireNonNull(target, "target must not be null");
            if (type == null || type.isBlank()) {
                type = (vxmlUrl != null || inlineVxml != null) ? "application/voicexml+xml" : "application/moml+xml";
            }
        }

        public static DialogStart ofMoml(MsmlTarget target, MomlDialog dialog) {
            return new DialogStart(null, target, "application/moml+xml", dialog, null, null);
        }

        public static DialogStart ofMoml(String id, MsmlTarget target, MomlDialog dialog) {
            return new DialogStart(id, target, "application/moml+xml", dialog, null, null);
        }

        public static DialogStart ofVxmlUrl(MsmlTarget target, String vxmlUrl) {
            return new DialogStart(null, target, "application/voicexml+xml", null, vxmlUrl, null);
        }

        public static DialogStart ofInlineVxml(MsmlTarget target, String inlineVxml) {
            return new DialogStart(null, target, "application/voicexml+xml", null, null, inlineVxml);
        }

        public boolean isVoiceXml() {
            return type != null && type.toLowerCase().contains("voicexml");
        }

        public boolean isMoml() {
            return !isVoiceXml();
        }
    }

    /**
     * Ends an active dialog executing on a target (RFC 5707 Section 3.2).
     */
    record DialogEnd(String id, MsmlTarget target) implements MsmlCommand {
        public DialogEnd {
            Objects.requireNonNull(target, "target must not be null");
        }
    }

    /**
     * Creates a multi-party conference bridge on the media server (RFC 5707 Section 5.1).
     */
    record CreateConference(
            MsmlTarget target,
            String deleteWhen,
            AudioMixConfig audioMix
    ) implements MsmlCommand {
        public CreateConference {
            Objects.requireNonNull(target, "target must not be null");
            if (!target.isConference()) {
                throw new IllegalArgumentException("CreateConference target must be conf: prefix");
            }
            if (deleteWhen == null || deleteWhen.isBlank()) deleteWhen = "nomedia";
            if (audioMix == null) audioMix = AudioMixConfig.standardPcm();
        }

        public static CreateConference of(String confId) {
            return new CreateConference(MsmlTarget.conference(confId), "nomedia", AudioMixConfig.standardPcm());
        }
    }

    /**
     * Modifies properties of an existing conference bridge (RFC 5707 Section 5.2).
     */
    record ModifyConference(
            MsmlTarget target,
            AudioMixConfig audioMix
    ) implements MsmlCommand {
        public ModifyConference {
            Objects.requireNonNull(target, "target must not be null");
        }
    }

    /**
     * Destroys an existing conference bridge and releases media resources (RFC 5707 Section 5.3).
     */
    record DestroyConference(MsmlTarget target) implements MsmlCommand {
        public DestroyConference {
            Objects.requireNonNull(target, "target must not be null");
        }

        public static DestroyConference of(String confId) {
            return new DestroyConference(MsmlTarget.conference(confId));
        }
    }

    /**
     * Joins two streams/endpoints (e.g. conn to conf, or conn to conn) (RFC 5707 Section 6.1).
     */
    record Join(
            MsmlTarget id1,
            MsmlTarget id2,
            StreamDirection direction,
            String media
    ) implements MsmlCommand {
        public Join {
            Objects.requireNonNull(id1, "id1 must not be null");
            Objects.requireNonNull(id2, "id2 must not be null");
            if (direction == null) direction = StreamDirection.SENDRECV;
            if (media == null || media.isBlank()) media = "audio";
        }

        public static Join of(MsmlTarget id1, MsmlTarget id2) {
            return new Join(id1, id2, StreamDirection.SENDRECV, "audio");
        }

        public static Join of(MsmlTarget id1, MsmlTarget id2, StreamDirection direction) {
            return new Join(id1, id2, direction, "audio");
        }
    }

    /**
     * Unjoins previously connected streams (RFC 5707 Section 6.2).
     */
    record Unjoin(MsmlTarget id1, MsmlTarget id2) implements MsmlCommand {
        public Unjoin {
            Objects.requireNonNull(id1, "id1 must not be null");
            Objects.requireNonNull(id2, "id2 must not be null");
        }

        public static Unjoin of(MsmlTarget id1, MsmlTarget id2) {
            return new Unjoin(id1, id2);
        }
    }

    /**
     * Audits state of a connection or conference (RFC 5707 Section 7).
     */
    record Audit(MsmlTarget target) implements MsmlCommand {
        public Audit {
            Objects.requireNonNull(target, "target must not be null");
        }
    }
}
