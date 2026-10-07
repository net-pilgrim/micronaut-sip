package net.pilgrim.mailbox.service;

import net.pilgrim.mailbox.config.MailboxConfiguration;
import net.pilgrim.mailbox.media.RtpVxmlMedia;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.rtp.media.AudioRecording;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.vxml.ast.VxmlDocument;
import net.pilgrim.vxml.loader.VxmlAudioLoader;
import net.pilgrim.vxml.loader.VxmlDocumentLoader;
import net.pilgrim.vxml.runtime.VxmlSession;
import net.pilgrim.vxml.speech.TtsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Manages the VoiceXML-driven answering machine call session.
 * <p>
 * The call flow logic is entirely defined by the VoiceXML document containing
 * a {@code <record>} form item. Upon VoiceXML dialog completion (or caller early hangup),
 * the captured audio is retrieved from dialog scope and persisted to Micronaut Object Storage.
 */
public class MailboxSession {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxSession.class);

    public enum State {
        READY,
        VXML_RUNNING,
        COMPLETED,
        TERMINATED
    }

    private final String callId;
    private final String mailboxOwner;
    private final String caller;
    private final RtpMediaSession mediaSession;
    private final RtpVxmlMedia vxmlMedia;
    private final MailboxRecordingService recordingService;
    private final MailboxConfiguration config;
    private final SipSession sipSession;
    private final SipRequest originalInvite;
    private final SipResponse originalOk;
    private final Consumer<MailboxSession> onCallFinished;

    private volatile State state = State.READY;
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    private VxmlSession vxmlSession;
    private String savedMessageKey;

    public MailboxSession(String callId,
                          String mailboxOwner,
                          String caller,
                          RtpMediaSession mediaSession,
                          MailboxRecordingService recordingService,
                          MailboxConfiguration config,
                          SipSession sipSession,
                          SipRequest originalInvite,
                          SipResponse originalOk,
                          Consumer<MailboxSession> onCallFinished) {
        this.callId = Objects.requireNonNull(callId, "callId");
        this.mailboxOwner = mailboxOwner;
        this.caller = caller != null ? caller : "anonymous";
        this.mediaSession = Objects.requireNonNull(mediaSession, "mediaSession");
        this.vxmlMedia = new RtpVxmlMedia(mediaSession);
        this.recordingService = Objects.requireNonNull(recordingService, "recordingService");
        this.config = config != null ? config : new MailboxConfiguration();
        this.sipSession = sipSession;
        this.originalInvite = originalInvite;
        this.originalOk = originalOk;
        this.onCallFinished = onCallFinished;
    }

    /**
     * Starts VoiceXML execution of the answering machine dialog.
     */
    public synchronized void start(VxmlDocument mailboxDoc,
                                   VxmlDocumentLoader documentLoader,
                                   VxmlAudioLoader audioLoader,
                                   TtsClient ttsClient) {
        if (state != State.READY) {
            return;
        }
        state = State.VXML_RUNNING;
        LOG.info("Starting VoiceXML-driven answering machine dialog for Call-ID: {}", callId);

        this.vxmlSession = new VxmlSession(
                callId,
                mailboxDoc,
                "inline:mailbox",
                documentLoader,
                audioLoader,
                ttsClient,
                vxmlMedia,
                this::onVxmlDialogComplete
        );

        if (mailboxOwner != null && !mailboxOwner.isBlank()) {
            vxmlSession.setSessionVariable("mailboxOwner", mailboxOwner);
        } else {
            vxmlSession.setSessionVariable("mailboxOwner", "");
        }

        vxmlSession.start();
    }

    /**
     * Invoked when the VoiceXML Form Interpretation Algorithm completes the dialog.
     */
    private synchronized void onVxmlDialogComplete() {
        if (state == State.TERMINATED) {
            return;
        }
        state = State.COMPLETED;
        LOG.info("VoiceXML dialog completed for Call-ID: {}. Persisting recorded voicemail.", callId);

        persistRecordedMessage();

        if (onCallFinished != null) {
            onCallFinished.accept(this);
        }
    }

    /**
     * Handles inbound DTMF digits from SIP INFO (in addition to inband audio & RFC 4733).
     */
    public void onDtmf(char digit) {
        if (vxmlSession != null) {
            vxmlSession.onDtmf(digit);
        }
    }

    /**
     * Handles caller hanging up (SIP BYE) while the dialog is running.
     */
    public synchronized void onCallerHangup() {
        LOG.info("Caller hung up during mailbox session for Call-ID: {}", callId);
        persistRecordedMessage();
        terminate();
    }

    /**
     * Extracts recorded audio from the VoiceXML dialog scope (or active recorder) and saves to object storage.
     */
    private void persistRecordedMessage() {
        if (savedMessageKey != null) {
            return; // Already persisted
        }

        byte[] pcmData = null;
        long durationMs = 0;

        // 1. Try VoiceXML dialog scope variable "msg" (or configured variable name)
        if (vxmlSession != null) {
            Object msgVar = vxmlSession.getDialogScope().get("msg");
            if (msgVar instanceof byte[] bytes && bytes.length > 0) {
                pcmData = bytes;
                Object durObj = vxmlSession.getDialogScope().get("msg$.duration");
                if (durObj instanceof Number num) {
                    durationMs = num.longValue();
                }
            }
        }

        // 2. Fallback: check if mediaSession has active or recent recording
        if ((pcmData == null || pcmData.length == 0) && mediaSession != null) {
            AudioRecording rec = mediaSession.stopRecording();
            if (rec != null && rec.getPcmData().length > 0) {
                pcmData = rec.getPcmData();
                durationMs = rec.getDurationMs();
            }
        }

        // Store message if valid audio was captured (> 300 ms)
        if (pcmData != null && pcmData.length >= 4800) { // at least 300ms at 8kHz 16-bit
            if (durationMs <= 0) {
                durationMs = (pcmData.length * 1000L) / 16000L;
            }
            AudioRecording recording = new AudioRecording(callId, pcmData, 8000, 1, durationMs);
            try {
                this.savedMessageKey = recordingService.storeMessage(callId, mailboxOwner, caller, recording);
                LOG.info("Persisted voicemail for Call-ID: {} to object storage (key: {})", callId, savedMessageKey);
            } catch (Exception e) {
                LOG.error("Failed uploading voicemail to object storage for Call-ID: {}", callId, e);
            }
        } else {
            LOG.info("No substantial audio captured for Call-ID: {} (bytes: {})", callId, pcmData == null ? 0 : pcmData.length);
        }
    }

    public synchronized void terminate() {
        if (terminated.compareAndSet(false, true)) {
            state = State.TERMINATED;
            if (vxmlSession != null) {
                vxmlSession.terminate();
            }
            if (mediaSession != null) {
                mediaSession.stopAudio();
                mediaSession.stopRecording();
            }
            if (sipSession != null) {
                sipSession.setState(SipSession.State.TERMINATED);
            }
            LOG.info("MailboxSession terminated for Call-ID: {}", callId);
        }
    }

    public String getCallId() {
        return callId;
    }

    public String getCaller() {
        return caller;
    }

    public State getState() {
        return state;
    }

    public RtpMediaSession getMediaSession() {
        return mediaSession;
    }

    public RtpVxmlMedia getVxmlMedia() {
        return vxmlMedia;
    }

    public VxmlSession getVxmlSession() {
        return vxmlSession;
    }

    public String getSavedMessageKey() {
        return savedMessageKey;
    }

    public SipRequest getOriginalInvite() {
        return originalInvite;
    }

    public SipResponse getOriginalOk() {
        return originalOk;
    }

    public String getMailboxOwner() {
        return mailboxOwner;
    }

    public boolean isRecording() {
        return mediaSession != null && mediaSession.isRecording();
    }
}
