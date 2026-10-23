package net.pilgrim.webrtc;

import io.micronaut.websocket.WebSocketSession;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.netann.service.AnnouncementAudioLoader;
import net.pilgrim.vxml.ast.VxmlDocument;
import net.pilgrim.vxml.loader.VxmlDocumentLoader;
import net.pilgrim.vxml.media.VxmlMedia;
import net.pilgrim.vxml.runtime.VxmlSession;
import net.pilgrim.vxml.speech.DefaultTtsClient;
import net.pilgrim.vxml.speech.TtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class WebRtcVxmlBridge {

    private static final Logger LOG = LoggerFactory.getLogger(WebRtcVxmlBridge.class);

    private final VxmlDocumentLoader documentLoader;
    private final AnnouncementAudioLoader audioLoader;
    private final MistralTtsAdapter ttsClient;
    private final Map<String, VxmlSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> wsSessions = new ConcurrentHashMap<>();

    @Inject
    public WebRtcVxmlBridge(VxmlDocumentLoader documentLoader,
                            AnnouncementAudioLoader audioLoader,
                            MistralTtsAdapter ttsClient) {
        this.documentLoader = documentLoader;
        this.audioLoader = audioLoader;
        this.ttsClient = ttsClient;
    }

    public void handleSignal(String message, WebSocketSession wsSession) {
        try {
            if (message.contains("\"type\":\"offer\"")) {
                String vxmlUrl = extractField(message, "vxmlUrl");
                String sdp = extractField(message, "sdp");
                handleOffer(vxmlUrl, sdp, wsSession);
            } else if (message.contains("\"type\":\"answer\"")) {
                String sdp = extractField(message, "sdp");
                handleAnswer(sdp, wsSession);
            } else if (message.contains("\"type\":\"ice\"")) {
                String candidate = extractField(message, "candidate");
                handleIceCandidate(candidate, wsSession);
            } else if (message.contains("\"type\":\"dtmf\"")) {
                String digit = extractField(message, "digit");
                handleDtmf(digit, wsSession);
            }
        } catch (Exception e) {
            LOG.error("Error handling WebRTC signal", e);
            sendError(wsSession, "Signal processing failed");
        }
    }

    private void handleOffer(String vxmlUrl, String sdp, WebSocketSession wsSession) {
        // Offload to separate thread pool to allow blocking TTS calls
        CompletableFuture.runAsync(() -> {
            try {
                VxmlDocument doc = documentLoader.loadDocument(vxmlUrl);
                wsSessions.put(wsSession.getId(), wsSession);

                WebRtcVxmlMedia media = new WebRtcVxmlMedia(wsSession);

                VxmlSession vxmlSession = new VxmlSession(
                    wsSession.getId(),
                    doc,
                    vxmlUrl,
                    documentLoader,
                    audioLoader,
                    ttsClient,
                    media,
                    () -> {
                        LOG.info("VXML session ended for {}", wsSession.getId());
                        cleanup(wsSession.getId());
                        sendMessage(wsSession, "{\"type\":\"close\"}");
                    }
                );

                sessions.put(wsSession.getId(), vxmlSession);

                // Send answer SDP
                String answer = createAnswer(sdp);
                sendMessage(wsSession, "{\"type\":\"answer\",\"sdp\":\"" + escapeJson(answer) + "\"}");

                vxmlSession.start();

            } catch (Exception e) {
                LOG.error("Error processing offer for VXML: {}", vxmlUrl, e);
                sendError(wsSession, "VXML load failed: " + e.getMessage());
            }
        });
    }

    private void handleAnswer(String sdp, WebSocketSession wsSession) {
        LOG.debug("Received answer from {}", wsSession.getId());
    }

    private void handleIceCandidate(String candidate, WebSocketSession wsSession) {
        LOG.debug("Received ICE candidate from {}", wsSession.getId());
    }

    private String createAnswer(String offer) {
        try {
            // Parse offer to extract m-lines and their MIDs
            String[] lines = offer.split("\\r?\\n");

            java.util.List<String> mids = new java.util.ArrayList<>();
            String currentMid = null;
            boolean hasAudio = false;
            boolean hasDataChannel = false;

            for (String line : lines) {
                if (line.startsWith("m=audio ")) {
                    hasAudio = true;
                } else if (line.startsWith("m=application ")) {
                    hasDataChannel = true;
                } else if (line.startsWith("a=mid:")) {
                    currentMid = line.substring("a=mid:".length()).trim();
                    mids.add(currentMid);
                }
            }

            String bundleIds = String.join(" ", mids);
            long timestamp = System.currentTimeMillis();
            String iceUfrag = randomString(4);
            String icePwd = randomString(22);
            String fingerprint = randomFingerprint();

            String sessionPart = """
                v=0
                o=- %d 2 IN IP4 127.0.0.1
                s=WebRTC VXML
                t=0 0
                a=group:BUNDLE %s
                """.formatted(timestamp, bundleIds);

            StringBuilder answer = new StringBuilder(sessionPart);

            if (hasAudio && !mids.isEmpty()) {
                String audioPart = """
                    m=audio 9 UDP/TLS/RTP/SAVPF 0 8
                    c=IN IP4 0.0.0.0
                    a=rtcp:9 IN IP4 0.0.0.0
                    a=ice-ufrag:%s
                    a=ice-pwd:%s
                    a=ice-options:trickle
                    a=fingerprint:sha-256 %s
                    a=setup:active
                    a=mid:%s
                    a=sendonly
                    a=rtcp-mux
                    a=rtpmap:0 PCMU/8000
                    a=rtpmap:8 PCMA/8000
                    """.formatted(iceUfrag, icePwd, fingerprint, mids.get(0));
                answer.append(audioPart);
            }

            if (hasDataChannel && mids.size() > 1) {
                String dataPart = """
                    m=application 9 UDP/DTLS/SCTP webrtc-datachannel
                    c=IN IP4 0.0.0.0
                    a=ice-ufrag:%s
                    a=ice-pwd:%s
                    a=ice-options:trickle
                    a=fingerprint:sha-256 %s
                    a=setup:active
                    a=mid:%s
                    a=sctp-port:5000
                    a=max-message-size:262144
                    """.formatted(iceUfrag, icePwd, fingerprint, mids.get(1));
                answer.append(dataPart);
            }

            return answer.toString().replace("\n", "\r\n");
        } catch (Exception e) {
            LOG.error("Failed to create SDP answer", e);
            return offer.replace("a=sendrecv", "a=recvonly");
        }
    }

    private String randomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String randomFingerprint() {
        java.util.Random random = new java.util.Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%02X", random.nextInt(256)));
        }
        return sb.toString();
    }

    private void handleIncomingAudio(String base64Data, WebSocketSession wsSession) {
        VxmlSession session = sessions.get(wsSession.getId());
        if (session != null && session.getMedia() instanceof WebRtcVxmlMedia media) {
            byte[] pcm = java.util.Base64.getDecoder().decode(base64Data);
            media.onIncomingAudio(pcm);
        }
    }

    private void handleDtmf(String digit, WebSocketSession wsSession) {
        LOG.info("Received DTMF '{}' from WebSocket session: {}", digit, wsSession.getId());
        // Offload to separate thread to allow blocking TTS calls
        CompletableFuture.runAsync(() -> {
            VxmlSession session = sessions.get(wsSession.getId());
            LOG.info("VxmlSession for {} is {}", wsSession.getId(), session != null ? "present" : "null");
            if (session != null && !digit.isEmpty()) {
                LOG.info("Forwarding DTMF '{}' to VXML session", digit.charAt(0));
                session.onDtmf(digit.charAt(0));
            }
        });
    }

    public void cleanup(String sessionId) {
        VxmlSession session = sessions.remove(sessionId);
        if (session != null) {
            session.terminate();
        }
        wsSessions.remove(sessionId);
    }

    private void sendMessage(WebSocketSession session, String message) {
        if (session.isOpen()) {
            session.sendSync(message);
        }
    }

    private void sendError(WebSocketSession session, String error) {
        sendMessage(session, "{\"type\":\"error\",\"message\":\"" + error + "\"}");
    }

    private String extractField(String json, String field) {
        int start = json.indexOf("\"" + field + "\"");
        if (start == -1) return "";
        start = json.indexOf(":", start) + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return "";

        if (json.charAt(start) == '"') {
            start++;
            int end = json.indexOf("\"", start);
            return end > start ? json.substring(start, end) : "";
        } else if (json.charAt(start) == '{') {
            int depth = 1, pos = start + 1;
            while (pos < json.length() && depth > 0) {
                if (json.charAt(pos) == '{') depth++;
                else if (json.charAt(pos) == '}') depth--;
                pos++;
            }
            return json.substring(start, pos);
        }
        return "";
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

}
