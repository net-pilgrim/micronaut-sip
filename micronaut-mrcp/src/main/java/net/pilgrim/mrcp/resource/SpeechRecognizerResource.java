package net.pilgrim.mrcp.resource;

import io.micronaut.core.annotation.Nullable;
import net.pilgrim.mrcp.message.*;
import net.pilgrim.mrcp.mistral.MistralAudioClient;
import net.pilgrim.mrcp.mistral.MistralRecognitionResult;
import net.pilgrim.sip.rtp.media.AudioFrame;
import net.pilgrim.sip.rtp.media.AudioRecorder;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Speech Recognizer resource channel implementation (RFC 6787 §9)
 * powered by Mistral Audio models for speech recognition.
 */
public class SpeechRecognizerResource implements MrcpResource {

    private static final Logger LOG = LoggerFactory.getLogger(SpeechRecognizerResource.class);

    private final MrcpChannelId channelId;
    private final MistralAudioClient mistralClient;
    private final AtomicReference<RtpMediaSession> mediaSession = new AtomicReference<>();
    private final AtomicReference<AudioRecorder> activeRecorder = new AtomicReference<>();

    private final AtomicLong activeRequestId = new AtomicLong(0);
    private final AtomicBoolean recognizing = new AtomicBoolean(false);
    private final AtomicBoolean startOfInputFired = new AtomicBoolean(false);
    private final AtomicReference<Consumer<MrcpEvent>> currentEventSink = new AtomicReference<>();

    private final ByteArrayOutputStream manualAudioBuffer = new ByteArrayOutputStream();

    private volatile String activeGrammar = "";
    private volatile String defaultLanguage = "en-US";
    private volatile float confidenceThreshold = 0.5f;
    private volatile long noInputTimeoutMs = 5000L;
    private volatile MistralRecognitionResult lastResult;

    public SpeechRecognizerResource(MrcpChannelId channelId, MistralAudioClient mistralClient) {
        this(channelId, mistralClient, null);
    }

    public SpeechRecognizerResource(MrcpChannelId channelId,
                                    MistralAudioClient mistralClient,
                                    @Nullable RtpMediaSession mediaSession) {
        this.channelId = Objects.requireNonNull(channelId, "channelId must not be null");
        this.mistralClient = Objects.requireNonNull(mistralClient, "mistralClient must not be null");
        if (mediaSession != null) {
            this.mediaSession.set(mediaSession);
        }
    }

    public void attachMediaSession(RtpMediaSession session) {
        this.mediaSession.set(session);
    }

    public MistralRecognitionResult getLastResult() {
        return lastResult;
    }

    @Override
    public MrcpChannelId getChannelId() {
        return channelId;
    }

    @Override
    public MrcpResourceType getResourceType() {
        return MrcpResourceType.SPEECHRECOG;
    }

    @Override
    public Mono<MrcpResponse> handleRequest(MrcpRequest request, Consumer<MrcpEvent> eventSink) {
        currentEventSink.set(eventSink);
        MrcpMethod method = request.getMethod();
        String methodName = request.getMethodName();

        if (method == MrcpMethod.RECOGNIZE || "RECOGNIZE".equalsIgnoreCase(methodName)) {
            return handleRecognize(request, eventSink);
        } else if (method == MrcpMethod.STOP || "STOP".equalsIgnoreCase(methodName)) {
            return handleStop(request);
        } else if (method == MrcpMethod.START_INPUT_TIMERS || "START-INPUT-TIMERS".equalsIgnoreCase(methodName)) {
            return Mono.just(request.createResponse(MrcpStatusCode.SUCCESS, MrcpRequestState.COMPLETE));
        } else if (method == MrcpMethod.SET_PARAMS || "SET-PARAMS".equalsIgnoreCase(methodName)) {
            return handleSetParams(request);
        } else if (method == MrcpMethod.GET_PARAMS || "GET-PARAMS".equalsIgnoreCase(methodName)) {
            return handleGetParams(request);
        } else {
            return Mono.just(request.createResponse(MrcpStatusCode.METHOD_NOT_ALLOWED, MrcpRequestState.COMPLETE));
        }
    }

    private Mono<MrcpResponse> handleRecognize(MrcpRequest request, Consumer<MrcpEvent> eventSink) {
        long reqId = request.getRequestId();
        activeRequestId.set(reqId);
        recognizing.set(true);
        startOfInputFired.set(false);

        synchronized (manualAudioBuffer) {
            manualAudioBuffer.reset();
        }

        String body = request.getBodyAsString();
        if (body != null && !body.isBlank()) {
            this.activeGrammar = body.trim();
        }

        String lang = request.getHeaders().getSpeechLanguage();
        if (lang != null && !lang.isBlank()) {
            this.defaultLanguage = lang;
        }

        if (request.getHeaders().contains(MrcpHeaders.CONFIDENCE_THRESHOLD)) {
            this.confidenceThreshold = request.getHeaders().getConfidenceThreshold();
        }

        // Return 202 IN-PROGRESS immediately per RFC 6787 §9.5.1
        MrcpResponse inProgressResponse = request.createResponse(MrcpStatusCode.IN_PROGRESS, MrcpRequestState.IN_PROGRESS);

        // Attach recorder if media session is present
        RtpMediaSession rtpSession = mediaSession.get();
        if (rtpSession != null && !rtpSession.isClosed()) {
            AudioRecorder recorder = new AudioRecorder(channelId.getId(), AudioRecorder.DirectionFilter.INBOUND_ONLY, noInputTimeoutMs);
            activeRecorder.set(recorder);
            rtpSession.addAudioProcessor(recorder);
            recorder.start();
        }

        return Mono.just(inProgressResponse);
    }

    /**
     * Feeds captured PCM-16LE audio bytes into the active recognizer session.
     */
    public void feedAudioBytes(byte[] pcm16) {
        if (!recognizing.get() || pcm16 == null || pcm16.length == 0) {
            return;
        }

        // Fire START-OF-INPUT event once speech is detected
        if (startOfInputFired.compareAndSet(false, true)) {
            emitStartOfInput(activeRequestId.get(), currentEventSink.get());
        }

        synchronized (manualAudioBuffer) {
            manualAudioBuffer.write(pcm16, 0, pcm16.length);
        }
    }

    /**
     * Concludes the active audio input stream and triggers speech recognition via Mistral.
     */
    public Mono<MistralRecognitionResult> finishRecognition() {
        if (!recognizing.compareAndSet(true, false)) {
            return Mono.empty();
        }

        long reqId = activeRequestId.get();
        Consumer<MrcpEvent> eventSink = currentEventSink.get();

        // Stop recorder if active
        byte[] audioData;
        AudioRecorder recorder = activeRecorder.getAndSet(null);
        if (recorder != null) {
            audioData = recorder.stop().getPcmData();
        } else {
            synchronized (manualAudioBuffer) {
                audioData = manualAudioBuffer.toByteArray();
            }
        }

        return mistralClient.recognize(audioData, activeGrammar, defaultLanguage)
                .doOnNext(result -> {
                    this.lastResult = result;
                    emitRecognitionComplete(reqId, result, eventSink);
                })
                .doOnError(error -> {
                    LOG.error("Failed to recognize speech via Mistral: {}", error.getMessage(), error);
                    emitRecognitionFailed(reqId, eventSink);
                });
    }

    private Mono<MrcpResponse> handleStop(MrcpRequest request) {
        recognizing.set(false);
        AudioRecorder recorder = activeRecorder.getAndSet(null);
        if (recorder != null) {
            recorder.stop();
        }
        return Mono.just(request.createResponse(MrcpStatusCode.SUCCESS, MrcpRequestState.COMPLETE));
    }

    private Mono<MrcpResponse> handleSetParams(MrcpRequest request) {
        if (request.getHeaders().contains(MrcpHeaders.CONFIDENCE_THRESHOLD)) {
            confidenceThreshold = request.getHeaders().getConfidenceThreshold();
        }
        if (request.getHeaders().contains(MrcpHeaders.NO_INPUT_TIMEOUT)) {
            noInputTimeoutMs = request.getHeaders().getNoInputTimeout();
        }
        if (request.getHeaders().contains(MrcpHeaders.SPEECH_LANGUAGE)) {
            defaultLanguage = request.getHeaders().getSpeechLanguage();
        }
        return Mono.just(request.createResponse(MrcpStatusCode.SUCCESS, MrcpRequestState.COMPLETE));
    }

    private Mono<MrcpResponse> handleGetParams(MrcpRequest request) {
        MrcpResponse resp = request.createResponse(MrcpStatusCode.SUCCESS, MrcpRequestState.COMPLETE);
        resp.getHeaders().setConfidenceThreshold(confidenceThreshold);
        resp.getHeaders().setNoInputTimeout(noInputTimeoutMs);
        resp.getHeaders().setSpeechLanguage(defaultLanguage);
        return Mono.just(resp);
    }

    private void emitStartOfInput(long requestId, Consumer<MrcpEvent> eventSink) {
        if (eventSink == null) return;
        MrcpEvent event = new MrcpEvent(MrcpEventName.START_OF_INPUT, requestId, MrcpRequestState.IN_PROGRESS);
        event.getHeaders().setChannelIdentifier(channelId);
        try {
            eventSink.accept(event);
        } catch (Exception e) {
            LOG.warn("Failed to dispatch START-OF-INPUT event: {}", e.getMessage());
        }
    }

    private void emitRecognitionComplete(long requestId, MistralRecognitionResult result, Consumer<MrcpEvent> eventSink) {
        if (eventSink == null) return;
        MrcpEvent event = new MrcpEvent(MrcpEventName.RECOGNITION_COMPLETE, requestId, MrcpRequestState.COMPLETE);
        event.getHeaders().setChannelIdentifier(channelId);
        event.getHeaders().setCompletionCause("000 success");
        event.getHeaders().setContentType("application/nlsml+xml");
        event.setBody(result.toNlsml(), "application/nlsml+xml");

        try {
            eventSink.accept(event);
        } catch (Exception e) {
            LOG.warn("Failed to dispatch RECOGNITION-COMPLETE event: {}", e.getMessage());
        }
    }

    private void emitRecognitionFailed(long requestId, Consumer<MrcpEvent> eventSink) {
        if (eventSink == null) return;
        MrcpEvent event = new MrcpEvent(MrcpEventName.RECOGNITION_COMPLETE, requestId, MrcpRequestState.COMPLETE);
        event.getHeaders().setChannelIdentifier(channelId);
        event.getHeaders().setCompletionCause("004 no-match");
        try {
            eventSink.accept(event);
        } catch (Exception e) {
            LOG.warn("Failed to dispatch recognition failed event: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        recognizing.set(false);
        AudioRecorder recorder = activeRecorder.getAndSet(null);
        if (recorder != null) {
            recorder.stop();
        }
    }
}
