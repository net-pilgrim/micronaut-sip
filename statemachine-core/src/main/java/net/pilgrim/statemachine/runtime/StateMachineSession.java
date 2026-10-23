package net.pilgrim.statemachine.runtime;

import net.pilgrim.statemachine.action.Action;
import net.pilgrim.statemachine.action.VarAction;
import net.pilgrim.statemachine.eval.ExpressionEvaluator;
import net.pilgrim.statemachine.model.Event;
import net.pilgrim.statemachine.model.StateMachineDefinition;
import net.pilgrim.statemachine.model.Transition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Active execution runtime for an event-driven state machine.
 */
public class StateMachineSession {

    private static final Logger LOG = LoggerFactory.getLogger(StateMachineSession.class);

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "statemachine-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final String sessionId;
    private final StateMachineDefinition definition;
    private final String stateVariable;
    private volatile String currentState;

    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final List<StateMachineListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    private final Queue<Event> eventQueue = new ConcurrentLinkedQueue<>();
    private final Object processLock = new Object();
    private boolean isProcessing = false;

    public StateMachineSession(StateMachineDefinition definition) {
        this(UUID.randomUUID().toString(), definition);
    }

    public StateMachineSession(String sessionId, StateMachineDefinition definition) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.stateVariable = definition.stateVariable();
        this.currentState = definition.initialState();

        init();
    }

    private void init() {
        variables.put("sessionid", sessionId);
        variables.put(stateVariable, currentState);

        // Initialize variables
        for (VarAction var : definition.variables()) {
            var.execute(this, null);
        }

        // Run initial actions
        for (Action action : definition.initActions()) {
            action.execute(this, null);
        }

        // Sync initial state if updated by actions
        Object sv = variables.get(stateVariable);
        if (sv != null && !sv.toString().isBlank()) {
            this.currentState = sv.toString();
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    public StateMachineDefinition getDefinition() {
        return definition;
    }

    public String getCurrentState() {
        Object val = variables.get(stateVariable);
        if (val != null) {
            return val.toString();
        }
        return currentState;
    }

    public void setCurrentState(String newState) {
        String oldState = this.currentState;
        this.currentState = newState;
        variables.put(stateVariable, newState);
        if (!Objects.equals(oldState, newState)) {
            for (StateMachineListener l : listeners) {
                l.onStateChanged(oldState, newState, null);
            }
        }
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
            setCurrentState(value.toString());
        }
    }

    public void addListener(StateMachineListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(StateMachineListener listener) {
        listeners.remove(listener);
    }

    public boolean isTerminated() {
        return terminated.get();
    }

    public void terminate() {
        if (terminated.compareAndSet(false, true)) {
            LOG.debug("StateMachine session {} terminated", sessionId);
            for (ScheduledFuture<?> future : scheduledTasks.values()) {
                future.cancel(true);
            }
            scheduledTasks.clear();
            eventQueue.clear();
            for (StateMachineListener l : listeners) {
                l.onTerminated();
            }
        }
    }

    /**
     * Enqueues an event and processes pending events in FIFO order.
     *
     * @param event the event to process
     * @return true if a transition fired for this event
     */
    public boolean processEvent(Event event) {
        if (terminated.get() || event == null) {
            return false;
        }

        synchronized (processLock) {
            eventQueue.offer(event);
            if (isProcessing) {
                // Event enqueued during an active transition; outer loop will process it
                return true;
            }

            isProcessing = true;
            boolean matchedAny = false;
            try {
                while (!eventQueue.isEmpty()) {
                    Event currentEv = eventQueue.poll();
                    if (currentEv == null || terminated.get()) break;

                    boolean matched = dispatchEvent(currentEv);
                    if (matched) {
                        matchedAny = true;
                    }
                    for (StateMachineListener l : listeners) {
                        l.onEventProcessed(currentEv, matched);
                    }
                }
            } finally {
                isProcessing = false;
            }

            return matchedAny;
        }
    }

    private boolean dispatchEvent(Event event) {
        String activeState = getCurrentState();
        variables.put("event$", event);

        try {
            for (Transition transition : definition.transitions()) {
                if (transition.matches(activeState, event, variables)) {
                    LOG.debug("Session {}: transition matched [state={}, event={}]", sessionId, activeState, event.getName());
                    for (StateMachineListener l : listeners) {
                        l.onTransitionFired(transition, event);
                    }

                    transition.execute(this, event);

                    // Check if state changed
                    Object updatedState = variables.get(stateVariable);
                    if (updatedState != null && !updatedState.toString().equals(currentState)) {
                        String old = this.currentState;
                        this.currentState = updatedState.toString();
                        LOG.debug("Session {}: state transitioned from '{}' to '{}'", sessionId, old, currentState);
                        for (StateMachineListener l : listeners) {
                            l.onStateChanged(old, currentState, event);
                        }
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

    public void send(String eventName, String target, String targetType, String sendId, String delay, String namelist, Event currentEvent) {
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

        Event ev = new Event(resolvedEvent, sessionId, data);
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

    public void executeScript(String script, Event event) {
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
                Object val = ExpressionEvaluator.evaluate(expr, variables, eventScope);
                setVariable(varName, val);
            } else {
                ExpressionEvaluator.evaluate(trimmed, variables, eventScope);
            }
        }
    }

    private String resolveString(String exprOrLiteral, Event currentEvent) {
        if (exprOrLiteral == null) return null;
        String s = exprOrLiteral.trim();
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        Map<String, Object> eventScope = currentEvent != null ? Map.of("event$", currentEvent) : Collections.emptyMap();
        Object eval = ExpressionEvaluator.evaluate(s, variables, eventScope);
        return eval != null ? eval.toString() : s;
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
            } else if (str.endsWith("m")) {
                long min = Long.parseLong(str.substring(0, str.length() - 1).trim());
                return Duration.ofMinutes(min);
            } else {
                long ms = Long.parseLong(str);
                return Duration.ofMillis(ms);
            }
        } catch (NumberFormatException e) {
            return Duration.ZERO;
        }
    }
}
