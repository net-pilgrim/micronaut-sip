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
import net.pilgrim.sip.util.VirtualThreads;

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
    private String savedGreetingKey;

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
     * Starts VoiceXML execution of the answering machine dialog in default deposit mode.
     */
    public synchronized void start(VxmlDocument mailboxDoc,
                                   VxmlDocumentLoader documentLoader,
                                   VxmlAudioLoader audioLoader,
                                   TtsClient ttsClient) {
        start(mailboxDoc, documentLoader, audioLoader, ttsClient, "deposit");
    }

    /**
     * Starts VoiceXML execution of the answering machine dialog with a specified mode.
     *
     * @param mode "deposit", "manage", or "greeting"
     */
    public synchronized void start(VxmlDocument mailboxDoc,
                                   VxmlDocumentLoader documentLoader,
                                   VxmlAudioLoader audioLoader,
                                   TtsClient ttsClient,
                                   String mode) {
        if (state != State.READY) {
            return;
        }
        state = State.VXML_RUNNING;
        LOG.info("Starting VoiceXML-driven answering machine dialog for Call-ID: {}, mode: {}", callId, mode);

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

        String activeMode = mode != null ? mode : "deposit";
        vxmlSession.setSessionVariable("mode", activeMode);

        if (mailboxOwner != null && !mailboxOwner.isBlank()) {
            vxmlSession.setSessionVariable("mailboxOwner", mailboxOwner);
            vxmlSession.setSessionVariable("greetingUrl", "prov://" + mailboxOwner + "/greeting.wav");
            vxmlSession.setSessionVariable("hasGreeting", recordingService.hasCustomGreeting(mailboxOwner));
            int msgCount = recordingService.getOwnerMessageCount(mailboxOwner);
            vxmlSession.setSessionVariable("messageCount", msgCount);
        } else {
            vxmlSession.setSessionVariable("mailboxOwner", "");
            vxmlSession.setSessionVariable("greetingUrl", "");
            vxmlSession.setSessionVariable("hasGreeting", false);
            vxmlSession.setSessionVariable("messageCount", 0);
        }
        vxmlSession.setSessionVariable("caller", caller != null ? caller : "anonymous");

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
            VirtualThreads.run(() -> vxmlSession.onDtmf(digit));
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
        // 1. Check if a new greeting was recorded and accepted
        if (vxmlSession != null) {
            Object greetingVar = vxmlSession.getDialogScope().get("newGreeting");
            if (greetingVar instanceof byte[] gBytes && gBytes.length >= 4800) {
                Object termChar = vxmlSession.getDialogScope().get("newGreeting$.termchar");
                boolean cancelledGreeting = "*".equals(termChar) || "true".equalsIgnoreCase(String.valueOf(vxmlSession.getDialogScope().get("greetingCancelled")));
                if (!cancelledGreeting && mailboxOwner != null && !mailboxOwner.isBlank()) {
                    long gDur = 0;
                    Object durObj = vxmlSession.getDialogScope().get("newGreeting$.duration");
                    if (durObj instanceof Number num) gDur = num.longValue();
                    if (gDur <= 0) gDur = (gBytes.length * 1000L) / 16000L;
                    AudioRecording gRec = new AudioRecording(callId, gBytes, 8000, 1, gDur);
                    this.savedGreetingKey = recordingService.storeGreeting(mailboxOwner, gRec);
                    LOG.info("Persisted new greeting for owner: {} (key: {})", mailboxOwner, savedGreetingKey);
                }
            }

            // Check if reset greeting was requested
            Object resetGreetingFlag = vxmlSession.getDialogScope().get("resetGreeting");
            if (resetGreetingFlag != null && ("true".equalsIgnoreCase(String.valueOf(resetGreetingFlag)) || Boolean.TRUE.equals(resetGreetingFlag))) {
                if (mailboxOwner != null && !mailboxOwner.isBlank()) {
                    recordingService.deleteGreeting(mailboxOwner);
                    LOG.info("Reset greeting to default for owner: {}", mailboxOwner);
                }
            }

            // Check if message deletion was requested (from inbox management)
            Object delKey = vxmlSession.getDialogScope().get("deletedMessageKey");
            if (delKey instanceof String keyStr && !keyStr.isBlank()) {
                recordingService.deleteMessage(keyStr);
                LOG.info("Deleted voicemail message key: {}", keyStr);
            }
        }

        if (savedMessageKey != null) {
            return; // Already persisted
        }

        // Check if message was cancelled via '*' DTMF or cancelled dialog flag
        if (isCancelled()) {
            LOG.info("Voicemail for Call-ID: {} was cancelled by user ('*'). Discarding recording.", callId);
            return;
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

    public String getSavedGreetingKey() {
        return savedGreetingKey;
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

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    public boolean isCancelled() {
        if (vxmlSession == null) {
            return false;
        }
        Object termChar = vxmlSession.getDialogScope().get("msg$.termchar");
        if (termChar != null && ("*".equals(termChar) || "*".equals(String.valueOf(termChar).trim()))) {
            return true;
        }
        Object cancelledFlag = vxmlSession.getDialogScope().get("cancelled");
        if (cancelledFlag != null && ("true".equalsIgnoreCase(String.valueOf(cancelledFlag).trim()) || Boolean.TRUE.equals(cancelledFlag))) {
            return true;
        }
        return false;
    }
}
