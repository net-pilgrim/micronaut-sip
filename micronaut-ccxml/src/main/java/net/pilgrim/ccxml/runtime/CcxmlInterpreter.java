package net.pilgrim.ccxml.runtime;

import net.pilgrim.ccxml.ast.CcxmlDocument;
import net.pilgrim.ccxml.bridge.*;
import net.pilgrim.ccxml.parser.CcxmlParser;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-level CCXML interpreter coordinating session creation and bridge configurations.
 */
public class CcxmlInterpreter {

    private final CcxmlParser parser = new CcxmlParser();
    private final Map<String, CcxmlSession> activeSessions = new ConcurrentHashMap<>();

    private ConnectionBridge connectionBridge = new InMemoryConnectionBridge();
    private DialogBridge dialogBridge = new InMemoryDialogBridge();
    private MediaBridge mediaBridge = new InMemoryMediaBridge();

    public CcxmlInterpreter() {
    }

    public CcxmlInterpreter(ConnectionBridge connectionBridge, DialogBridge dialogBridge, MediaBridge mediaBridge) {
        if (connectionBridge != null) this.connectionBridge = connectionBridge;
        if (dialogBridge != null) this.dialogBridge = dialogBridge;
        if (mediaBridge != null) this.mediaBridge = mediaBridge;
    }

    public CcxmlSession createSession(String xml) {
        return createSession(UUID.randomUUID().toString(), parser.parse(xml));
    }

    public CcxmlSession createSession(InputStream in) {
        return createSession(UUID.randomUUID().toString(), parser.parse(in));
    }

    public CcxmlSession createSession(CcxmlDocument document) {
        return createSession(UUID.randomUUID().toString(), document);
    }

    public CcxmlSession createSession(String sessionId, CcxmlDocument document) {
        CcxmlSession session = new CcxmlSession(sessionId, document, connectionBridge, dialogBridge, mediaBridge);
        activeSessions.put(sessionId, session);
        return session;
    }

    public CcxmlSession getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }

    public void removeSession(String sessionId) {
        CcxmlSession session = activeSessions.remove(sessionId);
        if (session != null && !session.isTerminated()) {
            session.terminate();
        }
    }

    public void setConnectionBridge(ConnectionBridge connectionBridge) {
        this.connectionBridge = connectionBridge;
    }

    public void setDialogBridge(DialogBridge dialogBridge) {
        this.dialogBridge = dialogBridge;
    }

    public void setMediaBridge(MediaBridge mediaBridge) {
        this.mediaBridge = mediaBridge;
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
}
