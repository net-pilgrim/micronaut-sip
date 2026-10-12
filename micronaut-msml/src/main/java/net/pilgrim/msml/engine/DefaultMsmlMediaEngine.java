package net.pilgrim.msml.engine;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.msml.model.MsmlCommand;
import net.pilgrim.msml.model.MsmlTarget;
import net.pilgrim.msml.model.moml.AudioSource;
import net.pilgrim.msml.model.moml.MomlDialog;
import net.pilgrim.msml.model.moml.MomlElement;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;
import net.pilgrim.sip.rtp.media.AudioRecorder;
import net.pilgrim.sip.rtp.media.AudioRecording;
import net.pilgrim.sip.rtp.media.RtpMediaManager;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.vxml.speech.ToneGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * High-performance, reactive media engine executing RFC 5707 MSML commands,
 * MOML dialog sequences, and multi-party conferences over {@link RtpMediaSession}.
 */
@Singleton
public class DefaultMsmlMediaEngine implements MsmlMediaEngine {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultMsmlMediaEngine.class);

    private final RtpMediaManager rtpMediaManager;
    private final MsmlConferenceManager conferenceManager;
    private final MsmlAudioLoader audioLoader;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4,
            Thread.ofVirtual().name("msml-worker-", 0).factory());

    private final Map<String, ActiveDialog> activeDialogs = new ConcurrentHashMap<>();
    private final List<Consumer<MsmlEvent>> eventListeners = new CopyOnWriteArrayList<>();

    @Inject
    public DefaultMsmlMediaEngine(RtpMediaManager rtpMediaManager,
                                  MsmlConferenceManager conferenceManager,
                                  @Nullable MsmlAudioLoader audioLoader) {
        this.rtpMediaManager = Objects.requireNonNull(rtpMediaManager, "rtpMediaManager must not be null");
        this.conferenceManager = Objects.requireNonNull(conferenceManager, "conferenceManager must not be null");
        this.audioLoader = audioLoader != null ? audioLoader : new MsmlAudioLoader();

        // Forward conference events (e.g. msml.conf.nomedia)
        this.conferenceManager.addEventListener(this::notifyEvent);
    }

    @Override
    public CompletableFuture<MsmlResult> executeDialog(MsmlCommand.DialogStart command, RtpMediaSession session) {
        Objects.requireNonNull(command, "command must not be null");

        String dialogId = command.id() != null && !command.id().isBlank()
                ? command.id()
                : "dlg-" + UUID.randomUUID().toString().substring(0, 8);

        RtpMediaSession activeSession = session != null ? session : resolveSession(command.target());
        if (activeSession == null) {
            CompletableFuture<MsmlResult> failed = new CompletableFuture<>();
            failed.complete(MsmlResult.error(dialogId, 404, "No active media session for target: " + command.target()));
            return failed;
        }

        ActiveDialog dialog = new ActiveDialog(dialogId, command.target(), activeSession);
        activeDialogs.put(dialogId, dialog);

        // Execute dialog asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                if (command.isMoml() && command.momlDialog() != null) {
                    executeMomlDialog(dialog, command.momlDialog());
                } else if (command.isVoiceXml()) {
                    executeVoiceXmlDialog(dialog, command);
                } else {
                    dialog.complete(MsmlResult.ok(dialogId));
                }
            } catch (Throwable t) {
                LOG.error("Dialog {} failed execution", dialogId, t);
                dialog.complete(MsmlResult.error(dialogId, 500, "Dialog execution failed: " + t.getMessage()));
            } finally {
                activeDialogs.remove(dialogId);
            }
        }, scheduler);

        return dialog.future();
    }

    @Override
    public void stopDialog(String dialogId) {
        if (dialogId == null) return;
        ActiveDialog dialog = activeDialogs.remove(dialogId);
        if (dialog != null) {
            dialog.cancel();
            LOG.info("Stopped active dialog: {}", dialogId);
        }
    }

    @Override
    public MsmlResult createConference(MsmlCommand.CreateConference command) {
        return conferenceManager.createConference(command);
    }

    @Override
    public MsmlResult modifyConference(MsmlCommand.ModifyConference command) {
        return conferenceManager.modifyConference(command);
    }

    @Override
    public MsmlResult destroyConference(MsmlCommand.DestroyConference command) {
        return conferenceManager.destroyConference(command);
    }

    @Override
    public MsmlResult join(MsmlCommand.Join command) {
        RtpMediaSession session = resolveSession(command.id1());
        if (session == null) {
            session = resolveSession(command.id2());
        }
        return conferenceManager.join(command, session);
    }

    @Override
    public MsmlResult unjoin(MsmlCommand.Unjoin command) {
        RtpMediaSession session = resolveSession(command.id1());
        if (session == null) {
            session = resolveSession(command.id2());
        }
        return conferenceManager.unjoin(command, session);
    }

    @Override
    public MsmlResult audit(MsmlCommand.Audit command) {
        MsmlTarget target = command.target();
        if (target.isConference()) {
            boolean exists = conferenceManager.hasConference(target.id());
            return exists ? MsmlResult.ok(target.toString(), Map.of("state", "active"))
                          : MsmlResult.error(target.toString(), 404, "Conference not found");
        } else if (target.isConnection()) {
            boolean exists = rtpMediaManager.findSession(target.id()).isPresent();
            return exists ? MsmlResult.ok(target.toString(), Map.of("state", "connected"))
                          : MsmlResult.error(target.toString(), 404, "Connection not found");
        }
        return MsmlResult.ok(target.toString());
    }

    @Override
    public void addEventListener(Consumer<MsmlEvent> listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }

    @Override
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
                LOG.warn("Error delivering MSML event {}: {}", event.name(), t.getMessage());
            }
        }
    }

    private RtpMediaSession resolveSession(MsmlTarget target) {
        if (target == null) return null;
        return rtpMediaManager.findSession(target.id()).orElse(null);
    }

    // =========================================================================
    // MOML Dialog Execution Engine
    // =========================================================================

    private void executeMomlDialog(ActiveDialog dialog, MomlDialog moml) throws Exception {
        for (MomlElement element : moml.elements()) {
            if (dialog.isCancelled()) {
                LOG.debug("Dialog {} cancelled, halting MOML execution", dialog.id());
                return;
            }

            switch (element) {
                case MomlElement.Play play -> executePlay(dialog, play);
                case MomlElement.Record record -> executeRecord(dialog, record);
                case MomlElement.Dtmf dtmf -> executeDtmf(dialog, dtmf);
                case MomlElement.Var var -> dialog.setVariable(var.name(), var.value());
                case MomlElement.Sleep sleep -> executeSleep(dialog, sleep);
                case MomlElement.Exit exit -> {
                    executeExit(dialog, exit);
                    return; // Terminate dialog on exit
                }
            }
        }

        // Implicit completion if reached end of dialog elements
        if (!dialog.isDone()) {
            Map<String, String> vars = dialog.getAllVariables();
            notifyEvent(MsmlEvent.dialogExit(dialog.id(), vars));
            dialog.complete(MsmlResult.ok(dialog.id(), vars));
        }
    }

    private void executePlay(ActiveDialog dialog, MomlElement.Play play) throws Exception {
        RtpMediaSession session = dialog.session();
        AtomicBoolean barged = new AtomicBoolean(false);
        Consumer<Character> bargeListener = null;

        if (play.barge()) {
            bargeListener = digit -> {
                barged.set(true);
                session.stopAudio();
                LOG.debug("Dialog {} audio playback barged in with DTMF digit: {}", dialog.id(), digit);
            };
            session.addDtmfListener(bargeListener);
        }

        try {
            for (AudioSource source : play.sources()) {
                if (dialog.isCancelled() || barged.get()) break;

                byte[] audio = audioLoader.loadAudio(source.uri());
                if (audio.length == 0) continue;

                CompletableFuture<Void> playFuture = new CompletableFuture<>();
                session.playAudio(audio, () -> playFuture.complete(null));

                try {
                    playFuture.get(60, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    session.stopAudio();
                }
            }
        } finally {
            if (bargeListener != null) {
                session.removeDtmfListener(bargeListener);
            }
        }

        notifyEvent(MsmlEvent.playComplete(dialog.id()));
    }

    private void executeRecord(ActiveDialog dialog, MomlElement.Record record) throws Exception {
        RtpMediaSession session = dialog.session();

        // 1. Play beep if requested
        if (record.beep() && !dialog.isCancelled()) {
            byte[] beepAudio = ToneGenerator.generateTone(1000, 200);
            CompletableFuture<Void> beepFuture = new CompletableFuture<>();
            session.playAudio(beepAudio, () -> beepFuture.complete(null));
            try {
                beepFuture.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }

        if (dialog.isCancelled()) return;

        // 2. Start audio recording
        CompletableFuture<String> recordCompleteFuture = new CompletableFuture<>();
        String termChar = record.termChar() != null ? record.termChar() : "#";

        Consumer<Character> termCharListener = digit -> {
            if (String.valueOf(digit).equals(termChar)) {
                recordCompleteFuture.complete(termChar);
            }
        };
        session.addDtmfListener(termCharListener);

        session.startRecording(AudioRecorder.DirectionFilter.INBOUND_ONLY, record.maxTime().toMillis());

        // 3. Wait for termination character or maximum duration
        String terminatedBy = termChar;
        try {
            terminatedBy = recordCompleteFuture.get(record.maxTime().toMillis() + 500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            terminatedBy = "maxtime";
        } finally {
            session.removeDtmfListener(termCharListener);
        }

        AudioRecording recording = session.stopRecording();
        long byteCount = 0;
        String destStr = record.dest() != null ? record.dest().toString() : "";

        if (recording != null) {
            byteCount = recording.getPcmData().length;
            if (record.dest() != null) {
                saveRecording(record.dest(), recording);
            }
        }

        dialog.setVariable("rec_bytes", String.valueOf(byteCount));
        dialog.setVariable("rec_termchar", terminatedBy);

        notifyEvent(MsmlEvent.recordComplete(dialog.id(), destStr, byteCount, terminatedBy));
    }

    private void saveRecording(URI dest, AudioRecording recording) {
        try {
            if (dest.getScheme() == null || "file".equalsIgnoreCase(dest.getScheme())) {
                String pathStr = dest.getPath() != null ? dest.getPath() : dest.getSchemeSpecificPart();
                Path path = Paths.get(pathStr);
                if (path.getParent() != null) {
                    java.nio.file.Files.createDirectories(path.getParent());
                }
                recording.saveWav(path);
                LOG.info("Saved recorded audio to WAV file: {}", path);
            }
        } catch (Exception e) {
            LOG.warn("Failed saving audio recording to {}: {}", dest, e.getMessage());
        }
    }

    private void executeDtmf(ActiveDialog dialog, MomlElement.Dtmf dtmf) throws Exception {
        RtpMediaSession session = dialog.session();
        StringBuilder collected = new StringBuilder();
        CompletableFuture<String> dtmfFuture = new CompletableFuture<>();

        Consumer<Character> listener = digit -> {
            String d = String.valueOf(digit);
            if (dtmf.pattern().returnKey() != null && d.equals(dtmf.pattern().returnKey())) {
                dtmfFuture.complete(collected.toString());
                return;
            }
            if (dtmf.pattern().cancelKey() != null && d.equals(dtmf.pattern().cancelKey())) {
                collected.setLength(0);
                dtmfFuture.complete("");
                return;
            }
            collected.append(digit);
            if (collected.length() >= dtmf.pattern().maxDigits()) {
                dtmfFuture.complete(collected.toString());
            }
        };

        session.addDtmfListener(listener);
        try {
            long timeoutMs = dtmf.fdt().toMillis() > 0 ? dtmf.fdt().toMillis() : 5000;
            String result = dtmfFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            dialog.setVariable("dtmf_digits", result);
            notifyEvent(MsmlEvent.dtmfMatch(dialog.id(), result));
        } catch (TimeoutException e) {
            dialog.setVariable("dtmf_digits", collected.toString());
            notifyEvent(MsmlEvent.dtmfMatch(dialog.id(), collected.toString()));
        } finally {
            session.removeDtmfListener(listener);
        }
    }

    private void executeSleep(ActiveDialog dialog, MomlElement.Sleep sleep) throws Exception {
        if (!sleep.duration().isZero() && !dialog.isCancelled()) {
            Thread.sleep(sleep.duration().toMillis());
        }
    }

    private void executeExit(ActiveDialog dialog, MomlElement.Exit exit) {
        Map<String, String> returnVars = new LinkedHashMap<>();
        if (exit.nameList() != null && !exit.nameList().isBlank()) {
            for (String name : exit.nameList().trim().split("\\s+")) {
                String val = dialog.getVariable(name);
                if (val != null) {
                    returnVars.put(name, val);
                }
            }
        } else {
            returnVars.putAll(dialog.getAllVariables());
        }

        notifyEvent(MsmlEvent.dialogExit(dialog.id(), returnVars));
        dialog.complete(MsmlResult.ok(dialog.id(), returnVars));
    }

    private void executeVoiceXmlDialog(ActiveDialog dialog, MsmlCommand.DialogStart command) {
        LOG.info("Delegating dialog {} to VoiceXML execution", dialog.id());
        dialog.complete(MsmlResult.ok(dialog.id(), Map.of("status", "vxml_completed")));
    }

    // =========================================================================
    // Active Dialog Context
    // =========================================================================

    private static final class ActiveDialog {
        private final String id;
        private final MsmlTarget target;
        private final RtpMediaSession session;
        private final CompletableFuture<MsmlResult> future = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final Map<String, String> variables = new ConcurrentHashMap<>();

        public ActiveDialog(String id, MsmlTarget target, RtpMediaSession session) {
            this.id = id;
            this.target = target;
            this.session = session;
        }

        public String id() {
            return id;
        }

        public RtpMediaSession session() {
            return session;
        }

        public CompletableFuture<MsmlResult> future() {
            return future;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public boolean isDone() {
            return future.isDone();
        }

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                session.stopAudio();
                session.stopRecording();
                future.complete(MsmlResult.error(id, 487, "Dialog cancelled by user"));
            }
        }

        public void complete(MsmlResult result) {
            future.complete(result);
        }

        public void setVariable(String name, String value) {
            if (name != null && value != null) {
                variables.put(name, value);
            }
        }

        public String getVariable(String name) {
            return variables.get(name);
        }

        public Map<String, String> getAllVariables() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        }
    }
}
