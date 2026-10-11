package net.pilgrim.mrcp.resource;

import io.micronaut.core.annotation.Nullable;
import net.pilgrim.mrcp.message.*;
import net.pilgrim.mrcp.mistral.MistralAudioClient;
import net.pilgrim.sip.rtp.media.RtpAudioPlayer;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Speech Synthesizer resource channel implementation (RFC 6787 §8)
 * powered by Mistral Audio models for text-to-speech generation.
 */
public class SpeechSynthesizerResource implements MrcpResource {

    private static final Logger LOG = LoggerFactory.getLogger(SpeechSynthesizerResource.class);

    private final MrcpChannelId channelId;
    private final MistralAudioClient mistralClient;
    private final AtomicReference<RtpMediaSession> mediaSession = new AtomicReference<>();

    private final AtomicReference<RtpAudioPlayer> activePlayer = new AtomicReference<>();
    private final AtomicLong activeRequestId = new AtomicLong(0);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicReference<Consumer<MrcpEvent>> currentEventSink = new AtomicReference<>();
    private volatile byte[] lastSynthesizedAudio;

    private volatile String defaultVoice = "en_paul_neutral";
    private volatile String defaultLanguage = "en-US";

    public SpeechSynthesizerResource(MrcpChannelId channelId, MistralAudioClient mistralClient) {
        this(channelId, mistralClient, null);
    }

    public SpeechSynthesizerResource(MrcpChannelId channelId,
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

    public byte[] getLastSynthesizedAudio() {
        return lastSynthesizedAudio;
    }

    @Override
    public MrcpChannelId getChannelId() {
        return channelId;
    }

    @Override
    public MrcpResourceType getResourceType() {
        return MrcpResourceType.SPEECHSYNTH;
    }

    @Override
    public Mono<MrcpResponse> handleRequest(MrcpRequest request, Consumer<MrcpEvent> eventSink) {
        currentEventSink.set(eventSink);
        MrcpMethod method = request.getMethod();
        String methodName = request.getMethodName();

        if (method == MrcpMethod.SPEAK || "SPEAK".equalsIgnoreCase(methodName)) {
            return handleSpeak(request, eventSink);
        } else if (method == MrcpMethod.STOP || "STOP".equalsIgnoreCase(methodName)) {
            return handleStop(request, "000 normal");
        } else if (method == MrcpMethod.BARGE_IN_OCCURRED || "BARGE-IN-OCCURRED".equalsIgnoreCase(methodName)) {
            return handleStop(request, "001 barge-in");
        } else if (method == MrcpMethod.PAUSE || "PAUSE".equalsIgnoreCase(methodName)) {
            return handlePause(request);
        } else if (method == MrcpMethod.RESUME || "RESUME".equalsIgnoreCase(methodName)) {
            return handleResume(request);
        } else if (method == MrcpMethod.SET_PARAMS || "SET-PARAMS".equalsIgnoreCase(methodName)) {
            return handleSetParams(request);
        } else if (method == MrcpMethod.GET_PARAMS || "GET-PARAMS".equalsIgnoreCase(methodName)) {
            return handleGetParams(request);
        } else {
            MrcpResponse resp = request.createResponse(MrcpStatusCode.METHOD_NOT_ALLOWED, MrcpRequestState.COMPLETE);
            return Mono.just(resp);
        }
    }

    private Mono<MrcpResponse> handleSpeak(MrcpRequest request, Consumer<MrcpEvent> eventSink) {
        stopActivePlayback();
        long reqId = request.getRequestId();
        activeRequestId.set(reqId);

        String text = request.getBodyAsString();
        String voice = request.getHeaders().getVoiceName();
        if (voice == null || voice.isBlank()) {
            voice = defaultVoice;
        }
        String lang = request.getHeaders().getSpeechLanguage();
        if (lang == null || lang.isBlank()) {
            lang = defaultLanguage;
        }

        // Return 202 IN-PROGRESS immediately per RFC 6787 §8.5.1
        MrcpResponse inProgressResponse = request.createResponse(MrcpStatusCode.IN_PROGRESS, MrcpRequestState.IN_PROGRESS);

        // Synthesize via Mistral client asynchronously
        mistralClient.synthesize(text, voice, lang)
                .subscribe(audioBytes -> {
                    this.lastSynthesizedAudio = audioBytes;
                    RtpMediaSession rtpSession = mediaSession.get();

                    if (rtpSession != null && !rtpSession.isClosed() && audioBytes != null && audioBytes.length > 0) {
                        isPlaying.set(true);
                        RtpAudioPlayer player = new RtpAudioPlayer(rtpSession, audioBytes, () -> {
                            isPlaying.set(false);
                            emitSpeakComplete(reqId, "000 normal", eventSink);
                        });
                        activePlayer.set(player);
                        player.start();
                    } else {
                        // Media session not attached or empty audio; emit complete directly
                        emitSpeakComplete(reqId, "000 normal", eventSink);
                    }
                }, error -> {
                    LOG.error("Failed to synthesize speech via Mistral: {}", error.getMessage(), error);
                    emitSpeakComplete(reqId, "003 error", eventSink);
                });

        return Mono.just(inProgressResponse);
    }

    private Mono<MrcpResponse> handleStop(MrcpRequest request, String cause) {
        long reqId = activeRequestId.get();
        boolean wasPlaying = stopActivePlayback();

        MrcpResponse resp = request.createResponse(MrcpStatusCode.SUCCESS, MrcpRequestState.COMPLETE);
        if (wasPlaying) {
            Consumer<MrcpEvent> sink = currentEventSink.get();
            if (sink != null && reqId > 0) {
                emitSpeakComplete(reqId, cause, sink);
            }
        }
        return Mono.just(resp);
    }

    private Mono<MrcpResponse> handlePause(MrcpRequest request) {
        RtpAudioPlayer player = activePlayer.get();
        if (player != null) {
            player.stop();
        }
        return Mono.just(request.createResponse(MrcpStatusCode.SUCCESS, MrcpRequestState.COMPLETE));
    }

    private Mono<MrcpResponse> handleResume(MrcpRequest request) {
        RtpAudioPlayer player = activePlayer.get();
        if (player != null) {
            player.start();
        }
        return Mono.just(request.createResponse(MrcpStatusCode.SUCCESS, MrcpRequestState.COMPLETE));
    }

    private Mono<MrcpResponse> handleSetParams(MrcpRequest request) {
        String voice = request.getHeaders().getVoiceName();
        if (voice != null) defaultVoice = voice;
        String lang = request.getHeaders().getSpeechLanguage();
        if (lang != null) defaultLanguage = lang;
        return Mono.just(request.createResponse(MrcpStatusCode.SUCCESS, MrcpRequestState.COMPLETE));
    }

    private Mono<MrcpResponse> handleGetParams(MrcpRequest request) {
        MrcpResponse resp = request.createResponse(MrcpStatusCode.SUCCESS, MrcpRequestState.COMPLETE);
        resp.getHeaders().setVoiceName(defaultVoice);
        resp.getHeaders().setSpeechLanguage(defaultLanguage);
        return Mono.just(resp);
    }

    private void emitSpeakComplete(long requestId, String cause, Consumer<MrcpEvent> eventSink) {
        if (eventSink == null) return;
        MrcpEvent event = new MrcpEvent(MrcpEventName.SPEAK_COMPLETE, requestId, MrcpRequestState.COMPLETE);
        event.getHeaders().setChannelIdentifier(channelId);
        event.getHeaders().setCompletionCause(cause);
        try {
            eventSink.accept(event);
        } catch (Exception e) {
            LOG.warn("Failed to dispatch SPEAK-COMPLETE event: {}", e.getMessage());
        }
    }

    private boolean stopActivePlayback() {
        RtpAudioPlayer player = activePlayer.getAndSet(null);
        if (player != null) {
            player.stop();
            isPlaying.set(false);
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        stopActivePlayback();
    }
}
