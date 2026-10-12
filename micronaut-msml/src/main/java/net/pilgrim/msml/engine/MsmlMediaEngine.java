package net.pilgrim.msml.engine;

import net.pilgrim.msml.model.MsmlCommand;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;
import net.pilgrim.sip.rtp.media.RtpMediaSession;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Core media processing engine interface for MSML RFC 5707 commands.
 */
public interface MsmlMediaEngine {

    /**
     * Executes a dialog (MOML sequence or delegated VoiceXML) on the given RTP session.
     *
     * @param command dialogstart command
     * @param session RTP media session
     * @return CompletableFuture completing when the dialog terminates with its final MsmlResult
     */
    CompletableFuture<MsmlResult> executeDialog(MsmlCommand.DialogStart command, RtpMediaSession session);

    /**
     * Terminates an active dialog by ID.
     */
    void stopDialog(String dialogId);

    /**
     * Creates a conference bridge.
     */
    MsmlResult createConference(MsmlCommand.CreateConference command);

    /**
     * Modifies conference parameters.
     */
    MsmlResult modifyConference(MsmlCommand.ModifyConference command);

    /**
     * Destroys a conference bridge.
     */
    MsmlResult destroyConference(MsmlCommand.DestroyConference command);

    /**
     * Joins streams/endpoints.
     */
    MsmlResult join(MsmlCommand.Join command);

    /**
     * Unjoins streams/endpoints.
     */
    MsmlResult unjoin(MsmlCommand.Unjoin command);

    /**
     * Audits state of target connection or conference.
     */
    MsmlResult audit(MsmlCommand.Audit command);

    /**
     * Registers a listener for asynchronous MSML events (play complete, record complete, dtmf, dialog exit).
     */
    void addEventListener(Consumer<MsmlEvent> listener);

    /**
     * Unregisters an event listener.
     */
    void removeEventListener(Consumer<MsmlEvent> listener);
}
