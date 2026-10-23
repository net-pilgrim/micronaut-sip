package net.pilgrim.janus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket client for Janus Gateway REST API.
 * Handles communication with Janus for creating sessions, attaching plugins, and making SIP calls.
 */
public class JanusClient implements WebSocket.Listener {

    private static final Logger LOG = LoggerFactory.getLogger(JanusClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String janusUrl;
    private final HttpClient httpClient;
    private WebSocket webSocket;
    private final AtomicLong transactionCounter = new AtomicLong(0);
    private final Map<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final StringBuilder messageBuffer = new StringBuilder();

    private volatile Long sessionId;
    private volatile Long pluginHandleId;

    public JanusClient(String janusUrl) {
        this.janusUrl = janusUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Connects to Janus Gateway via WebSocket.
     */
    public Mono<Void> connect() {
        return Mono.fromFuture(() -> {
            LOG.info("Connecting to Janus Gateway at {}", janusUrl);
            return httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(janusUrl), this)
                    .thenApply(ws -> {
                        this.webSocket = ws;
                        LOG.info("Connected to Janus Gateway");
                        return null;
                    });
        });
    }

    /**
     * Creates a Janus session.
     */
    public Mono<Long> createSession() {
        return sendRequest(Map.of(
                "janus", "create",
                "transaction", generateTransactionId()
        )).map(response -> {
            long sessionId = response.path("data").path("id").asLong();
            this.sessionId = sessionId;
            LOG.info("Created Janus session: {}", sessionId);
            return sessionId;
        });
    }

    /**
     * Attaches to the SIP plugin.
     */
    public Mono<Long> attachSipPlugin() {
        if (sessionId == null) {
            return Mono.error(new IllegalStateException("No active session"));
        }

        return sendRequest(Map.of(
                "janus", "attach",
                "plugin", "janus.plugin.sip",
                "transaction", generateTransactionId(),
                "session_id", sessionId
        )).map(response -> {
            long handleId = response.path("data").path("id").asLong();
            this.pluginHandleId = handleId;
            LOG.info("Attached to SIP plugin with handle: {}", handleId);
            return handleId;
        });
    }

    /**
     * Registers a SIP account with Janus.
     */
    public Mono<JsonNode> registerSipAccount(String sipUri, String username, String password, String proxy) {
        if (sessionId == null || pluginHandleId == null) {
            return Mono.error(new IllegalStateException("Session or plugin not initialized"));
        }

        Map<String, Object> registerRequest = Map.of(
                "janus", "message",
                "transaction", generateTransactionId(),
                "session_id", sessionId,
                "handle_id", pluginHandleId,
                "body", Map.of(
                        "request", "register",
                        "username", sipUri,
                        "authuser", username,
                        "secret", password,
                        "proxy", proxy
                )
        );

        return sendRequest(registerRequest);
    }

    /**
     * Makes a SIP call through Janus.
     */
    public Mono<JsonNode> makeCall(String sipUri, String sdpOffer) {
        if (sessionId == null || pluginHandleId == null) {
            return Mono.error(new IllegalStateException("Session or plugin not initialized"));
        }

        Map<String, Object> callRequest = Map.of(
                "janus", "message",
                "transaction", generateTransactionId(),
                "session_id", sessionId,
                "handle_id", pluginHandleId,
                "body", Map.of(
                        "request", "call",
                        "uri", sipUri
                ),
                "jsep", Map.of(
                        "type", "offer",
                        "sdp", sdpOffer
                )
        );

        return sendRequest(callRequest);
    }

    /**
     * Hangs up a call.
     */
    public Mono<JsonNode> hangup() {
        if (sessionId == null || pluginHandleId == null) {
            return Mono.error(new IllegalStateException("Session or plugin not initialized"));
        }

        return sendRequest(Map.of(
                "janus", "message",
                "transaction", generateTransactionId(),
                "session_id", sessionId,
                "handle_id", pluginHandleId,
                "body", Map.of("request", "hangup")
        ));
    }

    /**
     * Sends a request to Janus and waits for the response.
     */
    private Mono<JsonNode> sendRequest(Map<String, Object> request) {
        String transactionId = (String) request.get("transaction");
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(transactionId, future);

        try {
            String json = OBJECT_MAPPER.writeValueAsString(request);
            LOG.debug("Sending to Janus: {}", json);
            webSocket.sendText(json, true);
        } catch (Exception e) {
            pendingRequests.remove(transactionId);
            future.completeExceptionally(e);
        }

        return Mono.fromFuture(future)
                .timeout(Duration.ofSeconds(30))
                .doOnError(e -> {
                    pendingRequests.remove(transactionId);
                    LOG.error("Request failed: {}", transactionId, e);
                });
    }

    /**
     * Generates a unique transaction ID.
     */
    private String generateTransactionId() {
        return "txn-" + transactionCounter.incrementAndGet();
    }

    /**
     * WebSocket listener: handles incoming text messages.
     */
    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);

        if (last) {
            String message = messageBuffer.toString();
            messageBuffer.setLength(0);

            try {
                JsonNode response = OBJECT_MAPPER.readTree(message);
                LOG.debug("Received from Janus: {}", message);

                String transactionId = response.path("transaction").asText();
                if (transactionId != null && !transactionId.isEmpty()) {
                    CompletableFuture<JsonNode> future = pendingRequests.remove(transactionId);
                    if (future != null) {
                        String janus = response.path("janus").asText();
                        if ("error".equals(janus)) {
                            String errorMsg = response.path("error").path("reason").asText("Unknown error");
                            future.completeExceptionally(new JanusException(errorMsg));
                        } else {
                            future.complete(response);
                        }
                    }
                }

                // Handle events (without transaction ID)
                String janus = response.path("janus").asText();
                if ("event".equals(janus)) {
                    handleEvent(response);
                }

            } catch (Exception e) {
                LOG.error("Failed to parse Janus response: {}", message, e);
            }
        }

        webSocket.request(1);
        return null;
    }

    /**
     * Handles Janus events (incoming calls, hangups, etc.).
     */
    private void handleEvent(JsonNode event) {
        JsonNode pluginData = event.path("plugindata");
        if (!pluginData.isMissingNode()) {
            String plugin = pluginData.path("plugin").asText();
            JsonNode data = pluginData.path("data");

            LOG.info("Janus event from {}: {}", plugin, data);

            String result = data.path("result").asText();
            if ("event".equals(result)) {
                String callEvent = data.path("event").asText();
                LOG.info("SIP call event: {}", callEvent);
            }
        }
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        LOG.error("WebSocket error", error);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        LOG.info("WebSocket closed: {} - {}", statusCode, reason);
        return null;
    }

    /**
     * Closes the connection to Janus.
     */
    public void close() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closing");
        }
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getPluginHandleId() {
        return pluginHandleId;
    }
}
