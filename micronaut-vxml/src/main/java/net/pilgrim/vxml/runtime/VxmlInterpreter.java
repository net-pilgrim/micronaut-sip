package net.pilgrim.vxml.runtime;

import net.pilgrim.vxml.media.VxmlMedia;

import java.util.Map;

/**
 * VoiceXML 2.1 Form Interpretation Algorithm (FIA) interpreter interface.
 * <p>
 * Decoupled from media streaming and transport protocols via {@link VxmlMedia}.
 */
public interface VxmlInterpreter {

    /**
     * Starts execution of the VoiceXML dialog.
     */
    void start();

    /**
     * Executes the Form Interpretation Algorithm (FIA) main loop.
     */
    void runFia();

    /**
     * Handles inbound DTMF key presses from signaling (SIP INFO) or RTP payload.
     *
     * @param digit DTMF character (e.g. '0'-'9', '*', '#', 'A'-'D')
     */
    void onDtmf(char digit);

    /**
     * Terminates dialog execution and cleans up resources.
     */
    void terminate();

    /**
     * Injects the media object into the interpreter.
     *
     * @param media decoupled media object
     */
    void setMedia(VxmlMedia media);

    /**
     * Returns the currently injected media object.
     *
     * @return active media object
     */
    VxmlMedia getMedia();

    /**
     * Returns the current interpreter execution state.
     *
     * @return interpreter state
     */
    VxmlSession.State getState();

    /**
     * Returns the active document-scoped variables.
     *
     * @return unmodifiable map of document-scoped variables
     */
    Map<String, Object> getDocumentScope();

    /**
     * Returns the active dialog/form-scoped variables.
     *
     * @return unmodifiable map of dialog-scoped variables
     */
    Map<String, Object> getDialogScope();

    /**
     * Returns the active session-scoped variables.
     *
     * @return unmodifiable map of session-scoped variables
     */
    default Map<String, Object> getSessionScope() {
        return java.util.Map.of();
    }

    /**
     * Sets a session-scoped variable.
     *
     * @param name  variable name
     * @param value variable value
     */
    default void setSessionVariable(String name, Object value) {
    }
}
