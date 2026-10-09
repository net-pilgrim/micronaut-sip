package net.pilgrim.sip.bdd.model;

import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages the state, registered actors, captured variables, and message trace for a single Cucumber scenario.
 */
public class ScenarioContext {

    private static final ThreadLocal<ScenarioContext> CURRENT = ThreadLocal.withInitial(ScenarioContext::new);
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)\\}");

    private final Map<String, VirtualUserAgent> actors = new ConcurrentHashMap<>();
    private final Map<String, String> variables = new ConcurrentHashMap<>();
    private final CallLadderRecorder recorder = new CallLadderRecorder();

    private volatile InetSocketAddress sutAddress;
    private volatile SipMessage lastSentMessage;
    private volatile SipMessage lastReceivedMessage;
    private volatile SipRequest lastRequest;
    private volatile SipResponse lastResponse;
    private volatile String currentCallId;

    public static ScenarioContext current() {
        return CURRENT.get();
    }

    public static void resetCurrent() {
        ScenarioContext ctx = CURRENT.get();
        if (ctx != null) {
            ctx.reset();
        }
        CURRENT.remove();
    }

    public void registerActor(VirtualUserAgent actor) {
        actors.put(actor.getName().toLowerCase(), actor);
    }

    public VirtualUserAgent getActor(String name) {
        if (name == null) {
            return null;
        }
        return actors.get(name.trim().toLowerCase());
    }

    public VirtualUserAgent requireActor(String name) {
        VirtualUserAgent actor = getActor(name);
        if (actor == null) {
            throw new IllegalArgumentException(String.format("Actor '%s' is not registered in this scenario. Available actors: %s",
                    name, actors.keySet()));
        }
        return actor;
    }

    public Map<String, VirtualUserAgent> getAllActors() {
        return actors;
    }

    public void setVariable(String name, String value) {
        if (name != null && value != null) {
            variables.put(name.trim(), value.trim());
        }
    }

    public String getVariable(String name) {
        return variables.get(name);
    }

    public String interpolate(String input) {
        if (input == null || !input.contains("${")) {
            return input;
        }
        Matcher matcher = VAR_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = variables.getOrDefault(varName, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public String getOrCreateCallId() {
        if (currentCallId == null) {
            currentCallId = UUID.randomUUID().toString() + "@sip-bdd.local";
        }
        return currentCallId;
    }

    public void setCurrentCallId(String callId) {
        this.currentCallId = callId;
    }

    public InetSocketAddress getSutAddress() {
        return sutAddress;
    }

    public void setSutAddress(InetSocketAddress sutAddress) {
        this.sutAddress = sutAddress;
    }

    public SipMessage getLastSentMessage() {
        return lastSentMessage;
    }

    public void setLastSentMessage(SipMessage lastSentMessage) {
        this.lastSentMessage = lastSentMessage;
        if (lastSentMessage instanceof SipRequest req) {
            this.lastRequest = req;
            if (req.getCallId() != null) {
                this.currentCallId = req.getCallId();
            }
        } else if (lastSentMessage instanceof SipResponse resp) {
            this.lastResponse = resp;
        }
    }

    public SipMessage getLastReceivedMessage() {
        return lastReceivedMessage;
    }

    public void setLastReceivedMessage(SipMessage lastReceivedMessage) {
        this.lastReceivedMessage = lastReceivedMessage;
        if (lastReceivedMessage instanceof SipRequest req) {
            this.lastRequest = req;
            if (req.getCallId() != null) {
                this.currentCallId = req.getCallId();
            }
        } else if (lastReceivedMessage instanceof SipResponse resp) {
            this.lastResponse = resp;
        }
    }

    public SipRequest getLastRequest() {
        return lastRequest;
    }

    public SipResponse getLastResponse() {
        return lastResponse;
    }

    public CallLadderRecorder getRecorder() {
        return recorder;
    }

    public void reset() {
        for (VirtualUserAgent actor : actors.values()) {
            try {
                actor.close();
            } catch (Exception ignored) {}
        }
        actors.clear();
        variables.clear();
        recorder.clear();
        sutAddress = null;
        lastSentMessage = null;
        lastReceivedMessage = null;
        lastRequest = null;
        lastResponse = null;
        currentCallId = null;
    }
}
