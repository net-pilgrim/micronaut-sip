package net.pilgrim.ccxml.bridge;

import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.vxml.ast.VxmlDocument;
import net.pilgrim.vxml.loader.DefaultVxmlAudioLoader;
import net.pilgrim.vxml.loader.VxmlAudioLoader;
import net.pilgrim.vxml.loader.VxmlDocumentLoader;
import net.pilgrim.vxml.parser.VxmlParser;
import net.pilgrim.vxml.runtime.VxmlSession;
import net.pilgrim.vxml.speech.AsrClient;
import net.pilgrim.vxml.speech.DefaultAsrClient;
import net.pilgrim.vxml.speech.DefaultTtsClient;
import net.pilgrim.vxml.speech.TtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * DialogBridge implementation binding CCXML dialog execution directly to
 * micronaut-vxml's VxmlSession engine.
 */
public class VxmlDialogBridge implements DialogBridge {

    private static final Logger LOG = LoggerFactory.getLogger(VxmlDialogBridge.class);

    private final VxmlParser vxmlParser = new VxmlParser();
    private final VxmlDocumentLoader documentLoader;
    private final VxmlAudioLoader audioLoader;
    private final TtsClient ttsClient;
    private final AsrClient asrClient;

    private final Map<String, VxmlDocument> preparedDialogs = new ConcurrentHashMap<>();
    private final Map<String, VxmlSession> activeSessions = new ConcurrentHashMap<>();

    private Consumer<CcxmlEvent> eventSink;

    public VxmlDialogBridge() {
        this(new VxmlDocumentLoader(), new DefaultVxmlAudioLoader(), new DefaultTtsClient(), new DefaultAsrClient());
    }

    public VxmlDialogBridge(VxmlDocumentLoader documentLoader,
                            VxmlAudioLoader audioLoader,
                            TtsClient ttsClient,
                            AsrClient asrClient) {
        this.documentLoader = documentLoader != null ? documentLoader : new VxmlDocumentLoader();
        this.audioLoader = audioLoader != null ? audioLoader : new DefaultVxmlAudioLoader();
        this.ttsClient = ttsClient != null ? ttsClient : new DefaultTtsClient();
        this.asrClient = asrClient != null ? asrClient : new DefaultAsrClient();
    }

    public void setEventSink(Consumer<CcxmlEvent> eventSink) {
        this.eventSink = eventSink;
    }

    @Override
    public Mono<Void> prepareDialog(String dialogId, String src, String type) {
        return Mono.fromRunnable(() -> {
            try {
                VxmlDocument doc;
                if (src.trim().startsWith("<")) {
                    doc = vxmlParser.parse(src);
                } else {
                    doc = documentLoader.loadDocument(src);
                }
                preparedDialogs.put(dialogId != null ? dialogId : src, doc);
                LOG.info("Prepared VoiceXML dialog: {}", dialogId);
            } catch (Exception e) {
                LOG.error("Failed to prepare VoiceXML dialog: {}", src, e);
                throw new RuntimeException("Failed to prepare dialog: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public Mono<String> startDialog(String src, String type, String connectionId, String conferenceId, Map<String, Object> params) {
        return Mono.fromCallable(() -> {
            String dialogId = "vxml_dlg_" + UUID.randomUUID().toString().substring(0, 8);
            VxmlDocument doc = preparedDialogs.get(src);
            if (doc == null) {
                if (src.trim().startsWith("<")) {
                    doc = vxmlParser.parse(src);
                } else {
                    doc = documentLoader.loadDocument(src);
                }
            }

            Runnable onComplete = () -> {
                LOG.info("VoiceXML dialog {} completed", dialogId);
                VxmlSession finished = activeSessions.remove(dialogId);
                if (eventSink != null) {
                    Map<String, Object> exitParams = new HashMap<>();
                    if (finished != null) {
                        exitParams.putAll(finished.getSessionScope());
                        exitParams.putAll(finished.getDialogScope());
                    }
                    CcxmlEvent exitEvent = new CcxmlEvent("dialog.exit", connectionId, dialogId, conferenceId, exitParams);
                    eventSink.accept(exitEvent);
                }
            };

            VxmlSession vxmlSession = new VxmlSession(
                    connectionId != null ? connectionId : dialogId,
                    doc,
                    src,
                    documentLoader,
                    audioLoader,
                    ttsClient,
                    asrClient,
                    null,
                    onComplete
            );

            if (params != null) {
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    vxmlSession.setSessionVariable(entry.getKey(), entry.getValue());
                }
            }

            activeSessions.put(dialogId, vxmlSession);
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    vxmlSession.start();
                } catch (Exception e) {
                    LOG.error("Error running VxmlSession {}", dialogId, e);
                }
            });

            if (eventSink != null) {
                eventSink.accept(new CcxmlEvent("dialog.started", connectionId, dialogId, conferenceId, Collections.emptyMap()));
            }

            return dialogId;
        });
    }

    @Override
    public Mono<Void> terminateDialog(String dialogId, boolean immediate) {
        return Mono.fromRunnable(() -> {
            VxmlSession session = activeSessions.remove(dialogId);
            if (session != null) {
                session.terminate();
            }
        });
    }

    public VxmlSession getActiveSession(String dialogId) {
        return activeSessions.get(dialogId);
    }
}
