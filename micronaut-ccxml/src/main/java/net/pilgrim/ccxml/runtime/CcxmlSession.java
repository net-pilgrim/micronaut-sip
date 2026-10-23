package net.pilgrim.ccxml.runtime;

import net.pilgrim.ccxml.ast.*;
import net.pilgrim.ccxml.bridge.*;
import net.pilgrim.ccxml.eval.CcxmlExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Active CCXML 1.0 session executing an event-driven state machine.
 */
public class CcxmlSession {

    private static final Logger LOG = LoggerFactory.getLogger(CcxmlSession.class);

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "ccxml-timer");
                t.setDaemon(true);
                return t;
            }
    );

    private final String sessionId;
    private final CcxmlDocument document;
    private final ConnectionBridge connectionBridge;
    private final DialogBridge dialogBridge;
    private final MediaBridge mediaBridge;

    private final String stateVariable;
    private volatile String currentState = "init";
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    private final Queue<CcxmlEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private final Object processLock = new Object();
    private boolean isProcessing = false;

    public CcxmlSession(CcxmlDocument document) {
        this(UUID.randomUUID().toString(), document, new InMemoryConnectionBridge(), new InMemoryDialogBridge(), new InMemoryMediaBridge());
    }

    public CcxmlSession(String sessionId, CcxmlDocument document,
                        ConnectionBridge connectionBridge,
                        DialogBridge dialogBridge,
                        MediaBridge mediaBridge) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.document = Objects.requireNonNull(document, "document must not be null");
        this.connectionBridge = connectionBridge != null ? connectionBridge : new InMemoryConnectionBridge();
        this.dialogBridge = dialogBridge != null ? dialogBridge : new InMemoryDialogBridge();
        this.mediaBridge = mediaBridge != null ? mediaBridge : new InMemoryMediaBridge();
        this.stateVariable = document.eventProcessor().statevariable();

        init();
    }

    private void init() {
        variables.put("sessionid", sessionId);
        variables.put(stateVariable, currentState);

        // Initialize document variables
        for (CcxmlVar var : document.vars()) {
            Object initialVal = null;
            if (var.expr() != null && !var.expr().isBlank()) {
                initialVal = CcxmlExpressionEvaluator.evaluate(var.expr(), variables);
            }
            variables.put(var.name(), initialVal != null ? initialVal : "");
        }

        // Execute top-level scripts
        for (CcxmlScript script : document.scripts()) {
            if (script.content() != null && !script.content().isBlank()) {
                executeScript(script.content(), null);
            }
        }

        // Sync initial state variable
        Object sv = variables.get(stateVariable);
        if (sv != null && !sv.toString().isBlank()) {
            this.currentState = sv.toString();
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getCurrentState() {
        Object val = variables.get(stateVariable);
        if (val != null) {
            return val.toString();
        }
        return currentState;
    }

    public void setCurrentState(String newState) {
        this.currentState = newState;
        variables.put(stateVariable, newState);
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    public void setVariable(String name, Object value) {
        if (value == null) {
            variables.remove(name);
        } else {
            variables.put(name, value);
        }
        if (stateVariable.equals(name) && value != null) {
            this.currentState = value.toString();
        }
    }

    public ConnectionBridge getConnectionBridge() {
        return connectionBridge;
    }

    public DialogBridge getDialogBridge() {
        return dialogBridge;
    }

    public MediaBridge getMediaBridge() {
        return mediaBridge;
    }

    public boolean isTerminated() {
        return terminated.get();
    }

    public void terminate() {
        if (terminated.compareAndSet(false, true)) {
            LOG.info("CCXML session {} terminated", sessionId);
            for (ScheduledFuture<?> future : scheduledTasks.values()) {
                future.cancel(true);
            }
            scheduledTasks.clear();
            eventQueue.clear();
        }
    }

    /**
     * Enqueues an event and processes pending events in FIFO order.
     *
     * @param event the event to process
     * @return true if a transition fired for this event
     */
    public boolean processEvent(CcxmlEvent event) {
        if (terminated.get() || event == null) {
            return false;
        }

        synchronized (processLock) {
            eventQueue.offer(event);
            if (isProcessing) {
                return true;
            }

            isProcessing = true;
            boolean matchedAny = false;
            try {
                while (!eventQueue.isEmpty()) {
                    CcxmlEvent currentEv = eventQueue.poll();
                    if (currentEv == null || terminated.get()) break;

                    boolean matched = dispatchEvent(currentEv);
                    if (matched) {
                        matchedAny = true;
                    }
                }
            } finally {
                isProcessing = false;
            }

            return matchedAny;
        }
    }

    private boolean dispatchEvent(CcxmlEvent event) {
        String activeState = getCurrentState();
        variables.put("event$", event);

        try {
            for (CcxmlTransition transition : document.eventProcessor().transitions()) {
                if (transition.matches(activeState, event, variables)) {
                    LOG.debug("Session {}: transition matched [state={}, event={}]", sessionId, activeState, event.getName());
                    transition.execute(this, event);

                    // Update state if changed during execution
                    Object updatedState = variables.get(stateVariable);
                    if (updatedState != null && !updatedState.toString().equals(currentState)) {
                        this.currentState = updatedState.toString();
                        LOG.debug("Session {}: state transitioned to '{}'", sessionId, currentState);
                    }
                    return true;
                }
            }
            LOG.debug("Session {}: no transition matched for event {} in state {}", sessionId, event.getName(), activeState);
            return false;
        } finally {
            variables.remove("event$");
        }
    }

    public void send(String eventName, String target, String targetType, String sendId, String delay, String namelist, CcxmlEvent currentEvent) {
        if (terminated.get()) return;

        String resolvedEvent = resolveString(eventName, currentEvent);
        if (resolvedEvent == null || resolvedEvent.isBlank()) {
            return;
        }

        Map<String, Object> data = new HashMap<>();
        if (namelist != null && !namelist.isBlank()) {
            for (String varName : namelist.trim().split("\\s+")) {
                if (variables.containsKey(varName)) {
                    data.put(varName, variables.get(varName));
                }
            }
        }

        String connId = currentEvent != null ? currentEvent.getConnectionId() : null;
        String dlgId = currentEvent != null ? currentEvent.getDialogId() : null;
        String confId = currentEvent != null ? currentEvent.getConferenceId() : null;

        CcxmlEvent ev = new CcxmlEvent(resolvedEvent, connId, dlgId, confId, data);
        String resolvedDelay = resolveString(delay, currentEvent);
        Duration waitTime = parseDuration(resolvedDelay);

        if (waitTime.isZero() || waitTime.isNegative()) {
            processEvent(ev);
        } else {
            String sid = sendId != null ? resolveString(sendId, currentEvent) : UUID.randomUUID().toString();
            ScheduledFuture<?> future = SCHEDULER.schedule(() -> {
                scheduledTasks.remove(sid);
                processEvent(ev);
            }, waitTime.toMillis(), TimeUnit.MILLISECONDS);
            scheduledTasks.put(sid, future);
        }
    }

    public void cancelScheduledEvent(String sendId) {
        if (sendId != null) {
            String sid = resolveString(sendId, null);
            ScheduledFuture<?> future = scheduledTasks.remove(sid);
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private String resolveString(String exprOrLiteral, CcxmlEvent currentEvent) {
        if (exprOrLiteral == null) return null;
        String s = exprOrLiteral.trim();
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        Map<String, Object> eventScope = currentEvent != null ? Map.of("event$", currentEvent) : Collections.emptyMap();
        Object eval = CcxmlExpressionEvaluator.evaluate(s, variables, eventScope);
        return eval != null ? eval.toString() : s;
    }

    // Telephony bridges
    public void createCall(String dest, String callerId, String timeoutStr, String connIdVar, String aai) {
        Duration timeout = parseDuration(timeoutStr);
        Map<String, Object> hints = new HashMap<>();
        if (aai != null) hints.put("aai", aai);

        connectionBridge.createCall(dest, callerId, timeout, hints)
                .subscribe(connId -> {
                    if (connIdVar != null && !connIdVar.isBlank() && connId != null) {
                        setVariable(connIdVar, connId);
                    }
                });
    }

    public void acceptCall(String connectionId, String hints) {
        connectionBridge.acceptCall(connectionId).subscribe();
    }

    public void rejectCall(String connectionId, int statusCode, String reason) {
        connectionBridge.rejectCall(connectionId, statusCode, reason).subscribe();
    }

    public void redirectCall(String connectionId, String dest, String reason) {
        connectionBridge.redirectCall(connectionId, dest, reason).subscribe();
    }

    public void disconnectCall(String connectionId, String reason) {
        connectionBridge.disconnectCall(connectionId, reason).subscribe();
    }

    // Dialog bridges
    public void startDialog(String src, String type, String dialogIdVar, String connectionId, String conferenceId, String namelist, Map<String, String> parameters) {
        Map<String, Object> params = new HashMap<>();
        if (namelist != null && !namelist.isBlank()) {
            for (String varName : namelist.trim().split("\\s+")) {
                if (variables.containsKey(varName)) {
                    params.put(varName, variables.get(varName));
                }
            }
        }
        if (parameters != null) {
            params.putAll(parameters);
        }

        dialogBridge.startDialog(src, type, connectionId, conferenceId, params)
                .subscribe(dlgId -> {
                    if (dialogIdVar != null && !dialogIdVar.isBlank() && dlgId != null) {
                        setVariable(dialogIdVar, dlgId);
                    }
                });
    }

    public void terminateDialog(String dialogId, boolean immediate) {
        dialogBridge.terminateDialog(dialogId, immediate).subscribe();
    }

    public void prepareDialog(String dialogId, String src, String type) {
        dialogBridge.prepareDialog(dialogId, src, type).subscribe();
    }

    // Media bridges
    public void createConference(String confIdVar, String confName, Integer reservedTalkers) {
        Map<String, Object> attrs = new HashMap<>();
        if (reservedTalkers != null) attrs.put("reservedTalkers", reservedTalkers);

        mediaBridge.createConference(confName, attrs)
                .subscribe(confId -> {
                    if (confIdVar != null && !confIdVar.isBlank() && confId != null) {
                        setVariable(confIdVar, confId);
                    }
                });
    }

    public void destroyConference(String conferenceId) {
        mediaBridge.destroyConference(conferenceId).subscribe();
    }

    public void join(String id1, String id2, DuplexMode duplex) {
        mediaBridge.join(id1, id2, duplex).subscribe();
    }

    public void unjoin(String id1, String id2) {
        mediaBridge.unjoin(id1, id2).subscribe();
    }

    public void executeScript(String script, CcxmlEvent event) {
        if (script == null || script.isBlank()) return;
        Map<String, Object> eventScope = event != null ? Map.of("event$", event) : Collections.emptyMap();
        String[] lines = script.split("[;\\n]+");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) continue;
            if (trimmed.contains("=")) {
                int eq = trimmed.indexOf('=');
                String varName = trimmed.substring(0, eq).trim();
                String expr = trimmed.substring(eq + 1).trim();
                Object val = CcxmlExpressionEvaluator.evaluate(expr, variables, eventScope);
                setVariable(varName, val);
            } else {
                CcxmlExpressionEvaluator.evaluate(trimmed, variables, eventScope);
            }
        }
    }

    private Duration parseDuration(String s) {
        if (s == null || s.isBlank()) {
            return Duration.ZERO;
        }
        String str = s.trim().toLowerCase();
        try {
            if (str.endsWith("ms")) {
                long ms = Long.parseLong(str.substring(0, str.length() - 2).trim());
                return Duration.ofMillis(ms);
            } else if (str.endsWith("s")) {
                long sec = Long.parseLong(str.substring(0, str.length() - 1).trim());
                return Duration.ofSeconds(sec);
            } else {
                long ms = Long.parseLong(str);
                return Duration.ofMillis(ms);
            }
        } catch (NumberFormatException e) {
            return Duration.ZERO;
        }
    }
}
