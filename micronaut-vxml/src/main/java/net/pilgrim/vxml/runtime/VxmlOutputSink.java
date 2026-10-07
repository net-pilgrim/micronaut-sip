package net.pilgrim.vxml.runtime;

import net.pilgrim.vxml.media.VxmlMedia;

/**
 * Output sink interface for VoiceXML session interactions (audio output, call termination).
 *
 * @deprecated Prefer decoupling {@link VxmlMedia} from dialog lifecycle callbacks.
 */
@Deprecated
public interface VxmlOutputSink extends VxmlMedia {

    /**
     * Called when the VoiceXML dialog completes or exits, triggering in-dialog BYE teardown.
     */
    void onDialogComplete();
}
