package net.pilgrim.vxml.runtime;

import net.pilgrim.vxml.ast.*;
import net.pilgrim.vxml.eval.VxmlExpressionEvaluator;
import net.pilgrim.vxml.grammar.GrammarMatchResult;
import net.pilgrim.vxml.grammar.VxmlGrammarMatcher;
import net.pilgrim.vxml.loader.DefaultVxmlAudioLoader;
import net.pilgrim.vxml.loader.VxmlAudioLoader;
import net.pilgrim.vxml.loader.VxmlDocumentLoader;
import net.pilgrim.vxml.speech.TtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * State machine and Form Interpretation Algorithm (FIA) execution engine
 * for an active VoiceXML 2.1 session bound to a SIP Call-ID.
 */
public class VxmlSession {

    private static final Logger LOG = LoggerFactory.getLogger(VxmlSession.class);

    private static final ScheduledExecutorService TIMER_SCHEDULER = Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "vxml-session-timer");
                t.setDaemon(true);
                return t;
            });

    public enum State {
        READY,
        RUNNING,
        WAITING_FOR_INPUT,
        TERMINATED
    }

    private final String callId;
    private final VxmlDocumentLoader documentLoader;
    private final VxmlAudioLoader audioLoader;
    private final TtsClient ttsClient;
    private final VxmlGrammarMatcher grammarMatcher;
    private final VxmlOutputSink outputSink;

    private final Map<String, Object> documentScope = new ConcurrentHashMap<>();
    private final Map<String, Object> dialogScope = new ConcurrentHashMap<>();
    private final Set<String> executedBlocks = ConcurrentHashMap.newKeySet();

    private VxmlDocument currentDoc;
    private String currentDocUri;
    private VxmlForm currentForm;
    private VxmlField activeField;
    private final StringBuilder dtmfBuffer = new StringBuilder();

    private volatile State state = State.READY;
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private ScheduledFuture<?> noInputTimer;

    public VxmlSession(String callId,
                       VxmlDocument initialDoc,
                       String initialDocUri,
                       VxmlDocumentLoader documentLoader,
                       VxmlAudioLoader audioLoader,
                       TtsClient ttsClient,
                       VxmlOutputSink outputSink) {
        this.callId = Objects.requireNonNull(callId, "callId cannot be null");
        this.currentDoc = Objects.requireNonNull(initialDoc, "initialDoc cannot be null");
        this.currentDocUri = initialDocUri;
        this.documentLoader = documentLoader != null ? documentLoader : new VxmlDocumentLoader();
        this.audioLoader = audioLoader != null ? audioLoader : new DefaultVxmlAudioLoader();
        this.ttsClient = ttsClient;
        this.grammarMatcher = new VxmlGrammarMatcher();
        this.outputSink = outputSink;
    }

    public synchronized void start() {
        if (state != State.READY) {
            return;
        }
        state = State.RUNNING;
        LOG.info("Starting VxmlSession for Call-ID: {}", callId);

        // 1. Initialize document scope
        initDocumentScope(currentDoc);

        // 2. Select initial form
        Optional<VxmlForm> initialForm = currentDoc.getFirstForm();
        if (initialForm.isEmpty()) {
            LOG.warn("No form found in VoiceXML document for Call-ID: {}. Terminating.", callId);
            terminate();
            return;
        }

        transitionToForm(initialForm.get());
        runFia();
    }

    private void initDocumentScope(VxmlDocument doc) {
        documentScope.clear();
        for (VxmlVar var : doc.getVars()) {
            Object val = eval(var.getExpr());
            if (val != null) {
                documentScope.put(var.getName(), val);
            }
        }
    }

    private void transitionToForm(VxmlForm form) {
        this.currentForm = form;
        this.dialogScope.clear();
        this.executedBlocks.clear();
        this.activeField = null;
        this.dtmfBuffer.setLength(0);

        LOG.debug("Transitioned to form '{}' for Call-ID: {}", form.getId(), callId);

        // Initialize form-level variables
        for (VxmlFormItem item : form.getItems()) {
            if (item instanceof VxmlVar var) {
                Object val = eval(var.getExpr());
                if (val != null) {
                    dialogScope.put(var.getName(), val);
                }
            }
        }
    }

    /**
     * Executes the Form Interpretation Algorithm (FIA) main loop.
     */
    public synchronized void runFia() {
        if (state == State.TERMINATED) {
            return;
        }

        state = State.RUNNING;

        while (state == State.RUNNING) {
            // Select Phase: find the next unsatisfied form item
            VxmlFormItem nextItem = selectNextItem();
            if (nextItem == null) {
                LOG.info("No more form items to execute in form '{}' for Call-ID: {}. Dialog complete.",
                        currentForm != null ? currentForm.getId() : "null", callId);
                terminate();
                return;
            }

            if (nextItem instanceof VxmlBlock block) {
                String blockKey = getItemKey(block);
                executedBlocks.add(blockKey);

                FlowControl control = executeExecutables(block.getExecutables());
                if (control.isExit()) {
                    terminate();
                    return;
                }
                if (control.isGoto()) {
                    handleGoto(control.getGotoTarget());
                    return;
                }
            } else if (nextItem instanceof VxmlField field) {
                activeField = field;
                dtmfBuffer.setLength(0);

                // Play field prompts
                byte[] promptAudio = renderPrompts(field.getPrompts());
                boolean bargeIn = isBargeInAllowed(field.getPrompts());
                long timeoutMs = getPromptTimeout(field.getPrompts());

                state = State.WAITING_FOR_INPUT;
                startNoInputTimer(timeoutMs);

                if (promptAudio.length > 0 && outputSink != null) {
                    outputSink.playAudio(promptAudio, bargeIn, () -> {
                        // Prompt finished playing, no-input timer is ticking
                    });
                }
                return;
            }
        }
    }

    private VxmlFormItem selectNextItem() {
        if (currentForm == null) return null;

        for (int i = 0; i < currentForm.getItems().size(); i++) {
            VxmlFormItem item = currentForm.getItems().get(i);
            if (item instanceof VxmlVar) {
                continue;
            }
            if (item instanceof VxmlBlock block) {
                String key = getItemKey(block);
                if (!executedBlocks.contains(key)) {
                    if (VxmlExpressionEvaluator.evaluateCondition(block.getCond(), dialogScope, documentScope)) {
                        return block;
                    }
                }
            } else if (item instanceof VxmlField field) {
                if (!dialogScope.containsKey(field.getName())) {
                    if (VxmlExpressionEvaluator.evaluateCondition(field.getCond(), dialogScope, documentScope)) {
                        return field;
                    }
                }
            }
        }
        return null;
    }

    private String getItemKey(VxmlFormItem item) {
        return (currentForm != null ? currentForm.getId() : "") + ":" + item.getName() + ":" + System.identityHashCode(item);
    }

    /**
     * Handles inbound DTMF key presses from SIP INFO or RTP payload.
     */
    public synchronized void onDtmf(char digit) {
        if (state == State.TERMINATED) {
            return;
        }

        LOG.info("Received DTMF '{}' for Call-ID: {} (activeField: {}, state: {})",
                digit, callId, activeField != null ? activeField.getName() : "none", state);

        // Barge-in check: stop audio if currently playing and barge-in allowed
        if (outputSink != null && outputSink.isAudioPlaying()) {
            if (outputSink.isBargeInAllowed()) {
                outputSink.stopAudio();
            } else {
                return; // Barge-in not permitted, ignore
            }
        }

        cancelNoInputTimer();
        dtmfBuffer.append(digit);

        if (activeField == null) {
            return;
        }

        GrammarMatchResult result = grammarMatcher.match(activeField, dtmfBuffer.toString());

        if (result.isMatch()) {
            String value = result.getValue();
            dialogScope.put(activeField.getName(), value);
            dtmfBuffer.setLength(0);

            if (result.getNextTarget() != null) {
                handleGoto(result.getNextTarget());
                return;
            }

            if (activeField.getFilled() != null) {
                FlowControl control = executeExecutables(activeField.getFilled().getExecutables());
                if (control.isExit()) {
                    terminate();
                    return;
                }
                if (control.isGoto()) {
                    handleGoto(control.getGotoTarget());
                    return;
                }
            }

            activeField = null;
            runFia();
        } else if (result.isIncomplete()) {
            // Need more digits (e.g. 4-digit PIN), restart timeout
            startNoInputTimer(5000L);
        } else if (result.isNoMatch()) {
            dtmfBuffer.setLength(0);
            if (activeField.getNoMatch() != null) {
                FlowControl control = executeExecutables(activeField.getNoMatch().getExecutables());
                if (control.isExit()) {
                    terminate();
                    return;
                }
                if (control.isGoto()) {
                    handleGoto(control.getGotoTarget());
                    return;
                }
            }
            // Reprompt active field
            runFia();
        }
    }

    private void onNoInput() {
        synchronized (this) {
            if (state == State.TERMINATED || activeField == null) {
                return;
            }
            LOG.info("No-input timeout reached for field '{}' (Call-ID: {})", activeField.getName(), callId);
            if (activeField.getNoInput() != null) {
                FlowControl control = executeExecutables(activeField.getNoInput().getExecutables());
                if (control.isExit()) {
                    terminate();
                    return;
                }
                if (control.isGoto()) {
                    handleGoto(control.getGotoTarget());
                    return;
                }
            }
            runFia();
        }
    }

    private void handleGoto(String next) {
        if (next == null || next.isBlank()) {
            terminate();
            return;
        }

        String target = next.trim();
        LOG.info("Processing <goto next='{}'> for Call-ID: {}", target, callId);

        if (target.startsWith("#")) {
            Optional<VxmlForm> nextForm = currentDoc.findForm(target);
            if (nextForm.isPresent()) {
                transitionToForm(nextForm.get());
                runFia();
            } else {
                LOG.warn("Target form '{}' not found in current document for Call-ID: {}", target, callId);
                terminate();
            }
            return;
        }

        // Intra-document or external document reference
        String[] parts = target.split("#", 2);
        String docUri = parts[0];
        String formId = parts.length > 1 ? "#" + parts[1] : null;

        String resolvedUri = documentLoader.resolveUri(currentDocUri, docUri);
        try {
            VxmlDocument newDoc = documentLoader.loadDocument(resolvedUri);
            this.currentDoc = newDoc;
            this.currentDocUri = resolvedUri;
            initDocumentScope(newDoc);

            Optional<VxmlForm> targetForm = (formId != null) ? newDoc.findForm(formId) : newDoc.getFirstForm();
            if (targetForm.isPresent()) {
                transitionToForm(targetForm.get());
                runFia();
            } else {
                LOG.warn("Target form '{}' not found in loaded document '{}'", formId, resolvedUri);
                terminate();
            }
        } catch (Exception e) {
            LOG.error("Failed loading target VoiceXML document '{}' for Call-ID: {}", resolvedUri, callId, e);
            terminate();
        }
    }

    private FlowControl executeExecutables(List<VxmlExecutable> executables) {
        if (executables == null) return FlowControl.continueFlow();

        for (VxmlExecutable exec : executables) {
            if (exec instanceof VxmlAssign assign) {
                Object val = eval(assign.getExpr());
                if (dialogScope.containsKey(assign.getName()) || !documentScope.containsKey(assign.getName())) {
                    dialogScope.put(assign.getName(), val != null ? val : "");
                } else {
                    documentScope.put(assign.getName(), val != null ? val : "");
                }
            } else if (exec instanceof VxmlVar var) {
                Object val = eval(var.getExpr());
                dialogScope.put(var.getName(), val != null ? val : "");
            } else if (exec instanceof VxmlClear clear) {
                if (clear.getNamelist().isEmpty()) {
                    dialogScope.clear();
                    executedBlocks.clear();
                } else {
                    for (String name : clear.getNamelist()) {
                        dialogScope.remove(name);
                    }
                }
            } else if (exec instanceof VxmlGoto gotoNode) {
                return FlowControl.gotoTarget(gotoNode.getNext());
            } else if (exec instanceof VxmlExit || exec instanceof VxmlDisconnect) {
                return FlowControl.exit();
            } else if (exec instanceof VxmlPrompt prompt) {
                byte[] audio = renderPrompts(List.of(prompt));
                if (audio.length > 0 && outputSink != null) {
                    CountDownLatch latch = new CountDownLatch(1);
                    outputSink.playAudio(audio, prompt.isBargeIn(), latch::countDown);
                    try {
                        latch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {}
                }
            } else if (exec instanceof VxmlIf ifNode) {
                FlowControl fc = executeIf(ifNode);
                if (fc.isExit() || fc.isGoto()) {
                    return fc;
                }
            }
        }
        return FlowControl.continueFlow();
    }

    private FlowControl executeIf(VxmlIf ifNode) {
        if (VxmlExpressionEvaluator.evaluateCondition(ifNode.getCond(), dialogScope, documentScope)) {
            return executeExecutables(ifNode.getThenExecutables());
        }

        for (VxmlElseIf elseIf : ifNode.getElseIfs()) {
            if (VxmlExpressionEvaluator.evaluateCondition(elseIf.getCond(), dialogScope, documentScope)) {
                return executeExecutables(elseIf.getExecutables());
            }
        }

        if (!ifNode.getElseExecutables().isEmpty()) {
            return executeExecutables(ifNode.getElseExecutables());
        }

        return FlowControl.continueFlow();
    }

    private byte[] renderPrompts(List<VxmlPrompt> prompts) {
        if (prompts == null || prompts.isEmpty()) {
            return new byte[0];
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (VxmlPrompt prompt : prompts) {
            if (!VxmlExpressionEvaluator.evaluateCondition(prompt.getCond(), dialogScope, documentScope)) {
                continue;
            }

            for (VxmlPromptContent content : prompt.getContents()) {
                if (content instanceof VxmlTextContent text) {
                    if (ttsClient != null) {
                        byte[] pcm = ttsClient.synthesize(text.getText(), null, null).block();
                        if (pcm != null && pcm.length > 0) {
                            out.writeBytes(pcm);
                        }
                    }
                } else if (content instanceof VxmlAudio audio) {
                    String src = audio.getSrc();
                    if (src == null && audio.getExpr() != null) {
                        Object evaluated = eval(audio.getExpr());
                        if (evaluated != null) src = evaluated.toString();
                    }
                    if (src != null && !src.isBlank()) {
                        try {
                            byte[] pcm = audioLoader.loadAudio(src);
                            if (pcm != null && pcm.length > 0) {
                                out.writeBytes(pcm);
                            }
                        } catch (Exception e) {
                            LOG.warn("Failed loading prompt audio '{}' for Call-ID: {}", src, callId);
                        }
                    }
                } else if (content instanceof VxmlValue value) {
                    Object evaluated = eval(value.getExpr());
                    if (evaluated != null && ttsClient != null) {
                        byte[] pcm = ttsClient.synthesize(evaluated.toString(), null, null).block();
                        if (pcm != null && pcm.length > 0) {
                            out.writeBytes(pcm);
                        }
                    }
                }
            }
        }
        return out.toByteArray();
    }

    private boolean isBargeInAllowed(List<VxmlPrompt> prompts) {
        if (prompts == null || prompts.isEmpty()) return true;
        for (VxmlPrompt p : prompts) {
            if (!p.isBargeIn()) return false;
        }
        return true;
    }

    private long getPromptTimeout(List<VxmlPrompt> prompts) {
        if (prompts == null || prompts.isEmpty()) return 5000L;
        return prompts.get(prompts.size() - 1).getTimeoutMs();
    }

    private void startNoInputTimer(long timeoutMs) {
        cancelNoInputTimer();
        noInputTimer = TIMER_SCHEDULER.schedule(this::onNoInput, timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void cancelNoInputTimer() {
        if (noInputTimer != null) {
            noInputTimer.cancel(false);
            noInputTimer = null;
        }
    }

    private Object eval(String expr) {
        return VxmlExpressionEvaluator.evaluate(expr, dialogScope, documentScope);
    }

    public synchronized void terminate() {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        state = State.TERMINATED;
        cancelNoInputTimer();

        if (outputSink != null) {
            outputSink.stopAudio();
            outputSink.onDialogComplete();
        }
        LOG.info("VxmlSession terminated for Call-ID: {}", callId);
    }

    public State getState() {
        return state;
    }

    public Map<String, Object> getDocumentScope() {
        return Collections.unmodifiableMap(documentScope);
    }

    public Map<String, Object> getDialogScope() {
        return Collections.unmodifiableMap(dialogScope);
    }

    private static class FlowControl {
        private final boolean isExit;
        private final String gotoTarget;

        private FlowControl(boolean isExit, String gotoTarget) {
            this.isExit = isExit;
            this.gotoTarget = gotoTarget;
        }

        static FlowControl continueFlow() {
            return new FlowControl(false, null);
        }

        static FlowControl exit() {
            return new FlowControl(true, null);
        }

        static FlowControl gotoTarget(String target) {
            return new FlowControl(false, target);
        }

        boolean isExit() {
            return isExit;
        }

        boolean isGoto() {
            return gotoTarget != null;
        }

        String getGotoTarget() {
            return gotoTarget;
        }
    }
}
