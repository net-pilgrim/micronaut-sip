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
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private final ConcurrentMap<String, String> definedGrammars = new ConcurrentHashMap<>();

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
        } else if (method == MrcpMethod.DEFINE_GRAMMAR || "DEFINE-GRAMMAR".equalsIgnoreCase(methodName)) {
            return handleDefineGrammar(request);
        } else if (method == MrcpMethod.INTERPRET || "INTERPRET".equalsIgnoreCase(methodName)) {
            return handleInterpret(request);
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
            String trimmedBody = body.trim();
            if (definedGrammars.containsKey(trimmedBody)) {
                this.activeGrammar = definedGrammars.get(trimmedBody);
            } else if (trimmedBody.startsWith("session:") && definedGrammars.containsKey(trimmedBody.substring(8))) {
                this.activeGrammar = definedGrammars.get(trimmedBody.substring(8));
            } else {
                this.activeGrammar = trimmedBody;
            }
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

    private Mono<MrcpResponse> handleDefineGrammar(MrcpRequest request) {
        String body = request.getBodyAsString();
        String contentId = request.getHeaders().getContentId();
        if (contentId == null || contentId.isBlank()) {
            contentId = "default";
        }
        if (body != null && !body.isBlank()) {
            String trimmed = body.trim();
            definedGrammars.put(contentId, trimmed);
            if (contentId.startsWith("session:")) {
                definedGrammars.put(contentId.substring(8), trimmed);
            } else {
                definedGrammars.put("session:" + contentId, trimmed);
            }
            this.activeGrammar = trimmed;
        }
        return Mono.just(request.createResponse(MrcpStatusCode.SUCCESS, MrcpRequestState.COMPLETE));
    }

    private Mono<MrcpResponse> handleInterpret(MrcpRequest request) {
        String body = request.getBodyAsString();
        String text = body != null ? body.trim() : "";
        MistralRecognitionResult result = new MistralRecognitionResult(text, 1.0f, defaultLanguage, Collections.emptyList());
        MrcpResponse resp = request.createResponse(MrcpStatusCode.SUCCESS, MrcpRequestState.COMPLETE);
        resp.getHeaders().setContentType("application/nlsml+xml");
        resp.setBody(result.toNlsml(), "application/nlsml+xml");
        return Mono.just(resp);
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
                    if (result != null && result.getConfidence() >= confidenceThreshold && result.getText() != null && !result.getText().isBlank()) {
                        emitRecognitionComplete(reqId, result, eventSink);
                    } else {
                        emitRecognitionFailed(reqId, eventSink);
                    }
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
