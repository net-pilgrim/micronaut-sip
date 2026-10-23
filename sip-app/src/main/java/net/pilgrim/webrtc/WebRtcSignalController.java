package net.pilgrim.webrtc;

import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ServerWebSocket("/webrtc/signal")
public class WebRtcSignalController {

    private static final Logger LOG = LoggerFactory.getLogger(WebRtcSignalController.class);
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final WebRtcVxmlBridge bridge;

    @Inject
    public WebRtcSignalController(WebRtcVxmlBridge bridge) {
        this.bridge = bridge;
    }

    @OnOpen
    public void onOpen(WebSocketSession session) {
        sessions.put(session.getId(), session);
        LOG.info("WebRTC client connected: {}", session.getId());
    }

    @OnMessage
    public void onMessage(String message, WebSocketSession session) {
        LOG.debug("WebRTC message from {}: {}", session.getId(), message);
        bridge.handleSignal(message, session);
    }

    @OnClose
    public void onClose(WebSocketSession session) {
        sessions.remove(session.getId());
        bridge.cleanup(session.getId());
        LOG.info("WebRTC client disconnected: {}", session.getId());
    }
}
