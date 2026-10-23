package net.pilgrim.ccxml.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory connection bridge for testing and lightweight CCXML execution.
 */
public class InMemoryConnectionBridge implements ConnectionBridge {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryConnectionBridge.class);

    private final AtomicInteger counter = new AtomicInteger(1000);
    private final Map<String, ConnectionRecord> connections = new ConcurrentHashMap<>();
    private final List<String> callLog = Collections.synchronizedList(new ArrayList<>());

    public record ConnectionRecord(
            String connectionId,
            String dest,
            String callerId,
            Duration timeout,
            String state,
            Map<String, Object> hints
    ) {}

    @Override
    public Mono<String> createCall(String dest, String callerId, Duration timeout, Map<String, Object> hints) {
        String connId = "conn_" + counter.incrementAndGet();
        ConnectionRecord record = new ConnectionRecord(connId, dest, callerId, timeout, "PROGRESSING", hints);
        connections.put(connId, record);
        callLog.add("CREATE_CALL:" + connId + "->" + dest);
        LOG.debug("Created outbound call leg: {} to {}", connId, dest);
        return Mono.just(connId);
    }

    @Override
    public Mono<Void> acceptCall(String connectionId) {
        callLog.add("ACCEPT:" + connectionId);
        if (connectionId != null && connections.containsKey(connectionId)) {
            ConnectionRecord rec = connections.get(connectionId);
            connections.put(connectionId, new ConnectionRecord(rec.connectionId(), rec.dest(), rec.callerId(), rec.timeout(), "CONNECTED", rec.hints()));
        }
        LOG.debug("Accepted call leg: {}", connectionId);
        return Mono.empty();
    }

    @Override
    public Mono<Void> rejectCall(String connectionId, int statusCode, String reason) {
        callLog.add("REJECT:" + connectionId + ":" + statusCode + ":" + reason);
        if (connectionId != null) {
            connections.remove(connectionId);
        }
        LOG.debug("Rejected call leg: {} (status {}, reason '{}')", connectionId, statusCode, reason);
        return Mono.empty();
    }

    @Override
    public Mono<Void> redirectCall(String connectionId, String dest, String reason) {
        callLog.add("REDIRECT:" + connectionId + "->" + dest);
        LOG.debug("Redirected call leg: {} to {}", connectionId, dest);
        return Mono.empty();
    }

    @Override
    public Mono<Void> disconnectCall(String connectionId, String reason) {
        callLog.add("DISCONNECT:" + connectionId + ":" + reason);
        if (connectionId != null) {
            connections.remove(connectionId);
        }
        LOG.debug("Disconnected call leg: {} (reason '{}')", connectionId, reason);
        return Mono.empty();
    }

    public List<String> getCallLog() {
        return Collections.unmodifiableList(new ArrayList<>(callLog));
    }

    public Map<String, ConnectionRecord> getConnections() {
        return Collections.unmodifiableMap(connections);
    }
}
