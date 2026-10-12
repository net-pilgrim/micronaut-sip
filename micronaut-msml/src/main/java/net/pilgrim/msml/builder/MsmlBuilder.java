package net.pilgrim.msml.builder;

import net.pilgrim.msml.model.*;
import net.pilgrim.msml.model.moml.*;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;

import java.net.URI;
import java.time.Duration;
import java.util.*;

/**
 * Fluent builder for RFC 5707 MSML documents, dialogs, conferences, results, and events.
 */
public final class MsmlBuilder {

    private MsmlBuilder() {}

    // =========================================================================
    // Dialog Builders
    // =========================================================================

    public static DialogStartBuilder dialogStart(String target) {
        return new DialogStartBuilder(MsmlTarget.parse(target));
    }

    public static DialogStartBuilder dialogStart(MsmlTarget target) {
        return new DialogStartBuilder(target);
    }

    public static MsmlDocument dialogEnd(String target, String dialogId) {
        return MsmlDocument.ofCommand(new MsmlCommand.DialogEnd(dialogId, MsmlTarget.parse(target)));
    }

    public static MsmlDocument dialogEnd(MsmlTarget target, String dialogId) {
        return MsmlDocument.ofCommand(new MsmlCommand.DialogEnd(dialogId, target));
    }

    // =========================================================================
    // Conference Builders
    // =========================================================================

    public static CreateConferenceBuilder createConference(String confId) {
        return new CreateConferenceBuilder(MsmlTarget.conference(confId));
    }

    public static CreateConferenceBuilder createConference(MsmlTarget target) {
        return new CreateConferenceBuilder(target);
    }

    public static MsmlDocument destroyConference(String confId) {
        return MsmlDocument.ofCommand(MsmlCommand.DestroyConference.of(confId));
    }

    public static MsmlDocument destroyConference(MsmlTarget target) {
        return MsmlDocument.ofCommand(new MsmlCommand.DestroyConference(target));
    }

    public static JoinBuilder join(String id1, String id2) {
        return new JoinBuilder(MsmlTarget.parse(id1), MsmlTarget.parse(id2));
    }

    public static JoinBuilder join(MsmlTarget id1, MsmlTarget id2) {
        return new JoinBuilder(id1, id2);
    }

    public static MsmlDocument unjoin(String id1, String id2) {
        return MsmlDocument.ofCommand(MsmlCommand.Unjoin.of(MsmlTarget.parse(id1), MsmlTarget.parse(id2)));
    }

    public static MsmlDocument unjoin(MsmlTarget id1, MsmlTarget id2) {
        return MsmlDocument.ofCommand(new MsmlCommand.Unjoin(id1, id2));
    }

    // =========================================================================
    // Result & Event Builders
    // =========================================================================

    public static ResultBuilder result(int responseCode, String message) {
        return new ResultBuilder(responseCode, message);
    }

    public static ResultBuilder resultOk() {
        return new ResultBuilder(200, "OK");
    }

    public static ResultBuilder resultError(int responseCode, String message) {
        return new ResultBuilder(responseCode, message);
    }

    public static EventBuilder event(String name, String id) {
        return new EventBuilder(name, id);
    }

    // =========================================================================
    // Inner Builder Implementations
    // =========================================================================

    public static final class DialogStartBuilder {
        private final MsmlTarget target;
        private String id;
        private String type;
        private final List<MomlElement> momlElements = new ArrayList<>();
        private String vxmlUrl;
        private String inlineVxml;

        public DialogStartBuilder(MsmlTarget target) {
            this.target = Objects.requireNonNull(target, "target must not be null");
        }

        public DialogStartBuilder id(String id) {
            this.id = id;
            return this;
        }

        public DialogStartBuilder type(String type) {
            this.type = type;
            return this;
        }

        public DialogStartBuilder play(String audioUri) {
            return play(audioUri, true);
        }

        public DialogStartBuilder play(String audioUri, boolean barge) {
            return play(AudioSource.of(audioUri), barge);
        }

        public DialogStartBuilder play(AudioSource source, boolean barge) {
            momlElements.add(new MomlElement.Play(null, Duration.ZERO, Duration.ZERO, barge, true, List.of(source)));
            return this;
        }

        public DialogStartBuilder play(AudioSource... sources) {
            momlElements.add(new MomlElement.Play(null, Duration.ZERO, Duration.ZERO, true, true, List.of(sources)));
            return this;
        }

        public DialogStartBuilder play(URI audioUri) {
            return play(AudioSource.of(audioUri), true);
        }

        public DialogStartBuilder play(URI audioUri, boolean barge) {
            return play(AudioSource.of(audioUri), barge);
        }

        public DialogStartBuilder play(MomlElement.Play play) {
            momlElements.add(play);
            return this;
        }

        public DialogStartBuilder record(String destUri) {
            return record(URI.create(destUri), Duration.ofSeconds(30), "#", true);
        }

        public DialogStartBuilder record(URI dest, Duration maxTime, String termChar) {
            return record(dest, maxTime, termChar, true);
        }

        public DialogStartBuilder record(URI dest, Duration maxTime, String termChar, boolean beep) {
            momlElements.add(new MomlElement.Record(null, dest, "audio/wav", maxTime, termChar, beep, false));
            return this;
        }

        public DialogStartBuilder record(MomlElement.Record record) {
            momlElements.add(record);
            return this;
        }

        public DialogStartBuilder dtmf(DtmfPattern pattern) {
            momlElements.add(MomlElement.Dtmf.ofPattern(pattern));
            return this;
        }

        public DialogStartBuilder dtmf(int minDigits, int maxDigits, String returnKey) {
            momlElements.add(MomlElement.Dtmf.ofPattern(new DtmfPattern(minDigits, maxDigits, returnKey, null)));
            return this;
        }

        public DialogStartBuilder dtmf(MomlElement.Dtmf dtmf) {
            momlElements.add(dtmf);
            return this;
        }

        public DialogStartBuilder var(String name, String value) {
            momlElements.add(new MomlElement.Var(name, value));
            return this;
        }

        public DialogStartBuilder sleep(Duration duration) {
            momlElements.add(MomlElement.Sleep.of(duration));
            return this;
        }

        public DialogStartBuilder exit() {
            momlElements.add(MomlElement.Exit.now());
            return this;
        }

        public DialogStartBuilder exit(String nameList) {
            momlElements.add(new MomlElement.Exit(nameList));
            return this;
        }

        public DialogStartBuilder vxmlUrl(String url) {
            this.vxmlUrl = url;
            this.type = "application/voicexml+xml";
            return this;
        }

        public DialogStartBuilder inlineVxml(String xml) {
            this.inlineVxml = xml;
            this.type = "application/voicexml+xml";
            return this;
        }

        public MsmlCommand.DialogStart buildCommand() {
            MomlDialog dialog = !momlElements.isEmpty() ? new MomlDialog(momlElements) : null;
            return new MsmlCommand.DialogStart(id, target, type, dialog, vxmlUrl, inlineVxml);
        }

        public MsmlDocument build() {
            return MsmlDocument.ofCommand(buildCommand());
        }

        public String toXml() {
            return MsmlSerializer.serialize(build());
        }
    }

    public static final class CreateConferenceBuilder {
        private final MsmlTarget target;
        private String deleteWhen = "nomedia";
        private String sampleRate = "8000";
        private int channels = 1;
        private String gain = "0";

        public CreateConferenceBuilder(MsmlTarget target) {
            this.target = Objects.requireNonNull(target, "target must not be null");
        }

        public CreateConferenceBuilder deleteWhen(String deleteWhen) {
            this.deleteWhen = deleteWhen;
            return this;
        }

        public CreateConferenceBuilder sampleRate(String sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        public CreateConferenceBuilder channels(int channels) {
            this.channels = channels;
            return this;
        }

        public CreateConferenceBuilder gain(String gain) {
            this.gain = gain;
            return this;
        }

        public MsmlCommand.CreateConference buildCommand() {
            AudioMixConfig config = new AudioMixConfig(sampleRate, channels, gain);
            return new MsmlCommand.CreateConference(target, deleteWhen, config);
        }

        public MsmlDocument build() {
            return MsmlDocument.ofCommand(buildCommand());
        }

        public String toXml() {
            return MsmlSerializer.serialize(build());
        }
    }

    public static final class JoinBuilder {
        private final MsmlTarget id1;
        private final MsmlTarget id2;
        private StreamDirection direction = StreamDirection.SENDRECV;
        private String media = "audio";

        public JoinBuilder(MsmlTarget id1, MsmlTarget id2) {
            this.id1 = Objects.requireNonNull(id1, "id1 must not be null");
            this.id2 = Objects.requireNonNull(id2, "id2 must not be null");
        }

        public JoinBuilder direction(StreamDirection direction) {
            this.direction = direction;
            return this;
        }

        public JoinBuilder media(String media) {
            this.media = media;
            return this;
        }

        public MsmlCommand.Join buildCommand() {
            return new MsmlCommand.Join(id1, id2, direction, media);
        }

        public MsmlDocument build() {
            return MsmlDocument.ofCommand(buildCommand());
        }

        public String toXml() {
            return MsmlSerializer.serialize(build());
        }
    }

    public static final class ResultBuilder {
        private String id;
        private final int responseCode;
        private final String message;
        private final Map<String, String> data = new LinkedHashMap<>();

        public ResultBuilder(int responseCode, String message) {
            this.responseCode = responseCode;
            this.message = message;
        }

        public ResultBuilder id(String id) {
            this.id = id;
            return this;
        }

        public ResultBuilder data(String key, String value) {
            this.data.put(key, value);
            return this;
        }

        public ResultBuilder data(Map<String, String> entries) {
            if (entries != null) {
                this.data.putAll(entries);
            }
            return this;
        }

        public MsmlResult buildResult() {
            return new MsmlResult(id, responseCode, message, data);
        }

        public MsmlDocument build() {
            return MsmlDocument.ofResult(buildResult());
        }

        public String toXml() {
            return MsmlSerializer.serialize(build());
        }
    }

    public static final class EventBuilder {
        private final String name;
        private final String id;
        private final Map<String, String> params = new LinkedHashMap<>();

        public EventBuilder(String name, String id) {
            this.name = Objects.requireNonNull(name, "name must not be null");
            this.id = id;
        }

        public EventBuilder param(String key, String value) {
            this.params.put(key, value);
            return this;
        }

        public EventBuilder params(Map<String, String> entries) {
            if (entries != null) {
                this.params.putAll(entries);
            }
            return this;
        }

        public MsmlEvent buildEvent() {
            return new MsmlEvent(name, id, params);
        }

        public MsmlDocument build() {
            return MsmlDocument.ofEvent(buildEvent());
        }

        public String toXml() {
            return MsmlSerializer.serialize(build());
        }
    }
}
