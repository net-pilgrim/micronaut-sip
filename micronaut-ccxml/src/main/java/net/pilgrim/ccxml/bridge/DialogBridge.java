package net.pilgrim.ccxml.bridge;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Interface bridging CCXML dialog actions to interactive dialog engines (e.g. micronaut-vxml).
 */
public interface DialogBridge {

    /**
     * Prepares and pre-parses a dialog document for low-latency startup.
     *
     * @param dialogId client or system assigned dialog identifier
     * @param src document URI or inline source
     * @param type MIME type (e.g. "application/voicexml+xml")
     * @return Mono completing when prepared
     */
    Mono<Void> prepareDialog(String dialogId, String src, String type);

    /**
     * Starts an interactive VoiceXML dialog bound to a connection or conference.
     *
     * @param src document URI or inline source
     * @param type MIME type (e.g. "application/voicexml+xml")
     * @param connectionId the call leg connection ID
     * @param conferenceId optional conference ID if dialog is played into a conference room
     * @param params parameters passed to the dialog
     * @return Mono emitting the active dialogId
     */
    Mono<String> startDialog(String src, String type, String connectionId, String conferenceId, Map<String, Object> params);

    /**
     * Terminates an active dialog session.
     *
     * @param dialogId the dialog identifier
     * @param immediate whether to abort immediately or finish prompt
     * @return Mono completing when terminated
     */
    Mono<Void> terminateDialog(String dialogId, boolean immediate);
}
