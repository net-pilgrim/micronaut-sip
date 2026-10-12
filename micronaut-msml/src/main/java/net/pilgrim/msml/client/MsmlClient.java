package net.pilgrim.msml.client;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.msml.builder.MsmlBuilder;
import net.pilgrim.msml.builder.MsmlSerializer;
import net.pilgrim.msml.model.AudioMixConfig;
import net.pilgrim.msml.model.MsmlCommand;
import net.pilgrim.msml.model.MsmlDocument;
import net.pilgrim.msml.model.MsmlTarget;
import net.pilgrim.msml.model.StreamDirection;
import net.pilgrim.msml.model.moml.MomlDialog;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;
import net.pilgrim.msml.parser.MsmlParser;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Reactive client for driving remote Media Servers (MS) using RFC 5707 MSML.
 * Used on the Application Server (AS) to decouple compute-intensive media processing.
 */
@Singleton
public class MsmlClient {

    private static final Logger LOG = LoggerFactory.getLogger(MsmlClient.class);

    private final ReactiveSipClient sipClient;
    private final MsmlParser parser;
    private final AtomicLong cseqCounter = new AtomicLong(1);
    private final Sinks.Many<MsmlEvent> eventSink = Sinks.many().multicast().onBackpressureBuffer(1024, false);

    @Inject
    public MsmlClient(@Nullable ReactiveSipClient sipClient,
                      @Nullable MsmlParser parser) {
        this.sipClient = sipClient;
        this.parser = parser != null ? parser : new MsmlParser();
    }

    public MsmlClient(ReactiveSipClient sipClient) {
        this(sipClient, new MsmlParser());
    }

    // =========================================================================
    // Core Request Transmission
    // =========================================================================

    /**
     * Sends an MSML document to the remote Media Server inside a SIP INFO request.
     *
     * @param document MSML control document
     * @param destination Media Server network address
     * @param callId active call/dialog identifier
     * @return Mono emitting the Media Server's parsed {@link MsmlResult}
     */
    public Mono<MsmlResult> send(MsmlDocument document, InetSocketAddress destination, String callId) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(callId, "callId must not be null");

        if (sipClient == null) {
            return Mono.error(new IllegalStateException("ReactiveSipClient not configured in MsmlClient"));
        }

        String xmlBody = MsmlSerializer.serialize(document);
        String targetUri = "sip:msml@" + destination.getHostString() + ":" + destination.getPort();

        SipRequest infoReq = SipRequest.builder(SipMethod.INFO, targetUri)
                .callId(callId)
                .from("<sip:as-client@127.0.0.1>;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<" + targetUri + ">")
                .cseq(cseqCounter.incrementAndGet(), SipMethod.INFO)
                .contentType(MsmlDocument.CONTENT_TYPE)
                .body(xmlBody)
                .build();

        return sipClient.send(infoReq, destination)
                .flatMap(this::extractResultFromResponse);
    }

    private Mono<MsmlResult> extractResultFromResponse(SipResponse response) {
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            return Mono.just(MsmlResult.error(response.getStatusCode(), response.getReasonPhrase()));
        }

        String body = response.getBodyAsString();
        if (body == null || body.isBlank() || !body.contains("<msml")) {
            return Mono.just(MsmlResult.ok());
        }

        try {
            MsmlDocument doc = parser.parse(body);
            if (!doc.results().isEmpty()) {
                return Mono.just(doc.results().getFirst());
            }
            return Mono.just(MsmlResult.ok());
        } catch (Exception e) {
            LOG.warn("Failed parsing MSML response from Media Server: {}", e.getMessage());
            return Mono.just(MsmlResult.ok());
        }
    }

    // =========================================================================
    // High-Level Media Control APIs
    // =========================================================================

    public Mono<MsmlResult> startDialog(MsmlCommand.DialogStart command, InetSocketAddress destination, String callId) {
        return send(MsmlDocument.ofCommand(command), destination, callId);
    }

    public Mono<MsmlResult> startMomlDialog(String callId, InetSocketAddress destination, MomlDialog dialog) {
        MsmlDocument doc = MsmlDocument.ofCommand(MsmlCommand.DialogStart.ofMoml(MsmlTarget.connection(callId), dialog));
        return send(doc, destination, callId);
    }

    public Mono<MsmlResult> playAudio(String callId, InetSocketAddress destination, String audioUri) {
        return playAudio(callId, destination, audioUri, true);
    }

    public Mono<MsmlResult> playAudio(String callId, InetSocketAddress destination, String audioUri, boolean barge) {
        MsmlDocument doc = MsmlBuilder.dialogStart("conn:" + callId)
                .id("play-" + UUID.randomUUID().toString().substring(0, 8))
                .play(audioUri, barge)
                .build();
        return send(doc, destination, callId);
    }

    public Mono<MsmlResult> playAudio(String callId, InetSocketAddress destination, URI audioUri) {
        return playAudio(callId, destination, audioUri.toString(), true);
    }

    public Mono<MsmlResult> playAudio(String callId, InetSocketAddress destination, URI audioUri, boolean barge) {
        return playAudio(callId, destination, audioUri.toString(), barge);
    }

    public Mono<MsmlResult> playAndRecord(String callId,
                                          InetSocketAddress destination,
                                          URI audioUri,
                                          URI dest,
                                          Duration maxTime,
                                          String termChar) {
        return playAndRecord(callId, destination, audioUri.toString(), dest, maxTime, termChar);
    }

    public Mono<MsmlResult> playAndRecord(String callId,
                                          InetSocketAddress destination,
                                          String audioUri,
                                          URI dest,
                                          Duration maxTime,
                                          String termChar) {
        MsmlDocument doc = MsmlBuilder.dialogStart("conn:" + callId)
                .id("rec-" + UUID.randomUUID().toString().substring(0, 8))
                .play(audioUri, true)
                .record(dest, maxTime, termChar, true)
                .exit("rec_bytes rec_termchar")
                .build();
        return send(doc, destination, callId);
    }

    public Mono<MsmlResult> collectDtmf(String callId,
                                        InetSocketAddress destination,
                                        int minDigits,
                                        int maxDigits,
                                        String returnKey) {
        MsmlDocument doc = MsmlBuilder.dialogStart("conn:" + callId)
                .id("dtmf-" + UUID.randomUUID().toString().substring(0, 8))
                .dtmf(minDigits, maxDigits, returnKey)
                .exit("dtmf_digits")
                .build();
        return send(doc, destination, callId);
    }

    public Mono<MsmlResult> endDialog(String callId, InetSocketAddress destination, String dialogId) {
        MsmlDocument doc = MsmlBuilder.dialogEnd("conn:" + callId, dialogId);
        return send(doc, destination, callId);
    }

    // =========================================================================
    // Conference APIs
    // =========================================================================

    public Mono<MsmlResult> createConference(InetSocketAddress destination, String confId) {
        return createConference(destination, confId, AudioMixConfig.standardPcm());
    }

    public Mono<MsmlResult> createConference(InetSocketAddress destination, String confId, AudioMixConfig mixConfig) {
        MsmlDocument doc = MsmlDocument.ofCommand(new MsmlCommand.CreateConference(
                MsmlTarget.conference(confId), "nomedia", mixConfig));
        return send(doc, destination, "conf-" + confId);
    }

    public Mono<MsmlResult> modifyConference(InetSocketAddress destination, String confId, AudioMixConfig mixConfig) {
        MsmlDocument doc = MsmlDocument.ofCommand(new MsmlCommand.ModifyConference(
                MsmlTarget.conference(confId), mixConfig));
        return send(doc, destination, "conf-" + confId);
    }

    public Mono<MsmlResult> destroyConference(InetSocketAddress destination, String confId) {
        MsmlDocument doc = MsmlBuilder.destroyConference(confId);
        return send(doc, destination, "conf-" + confId);
    }

    public Mono<MsmlResult> join(InetSocketAddress destination, String callId, String confId, StreamDirection direction) {
        MsmlDocument doc = MsmlBuilder.join("conn:" + callId, "conf:" + confId)
                .direction(direction)
                .build();
        return send(doc, destination, callId);
    }

    public Mono<MsmlResult> unjoin(InetSocketAddress destination, String callId, String confId) {
        MsmlDocument doc = MsmlBuilder.unjoin("conn:" + callId, "conf:" + confId);
        return send(doc, destination, callId);
    }

    public Mono<MsmlResult> audit(InetSocketAddress destination, MsmlTarget target) {
        MsmlDocument doc = MsmlDocument.ofCommand(new MsmlCommand.Audit(target));
        return send(doc, destination, target.id());
    }

    // =========================================================================
    // Asynchronous Event Stream
    // =========================================================================

    /**
     * Ingests an asynchronous event notification received from a remote Media Server.
     */
    public void onEventReceived(MsmlEvent event) {
        if (event != null) {
            eventSink.tryEmitNext(event);
        }
    }

    /**
     * Reactive Flux stream of all received MSML media events.
     */
    public Flux<MsmlEvent> events() {
        return eventSink.asFlux();
    }

    /**
     * Registers a callback listener for MSML media events.
     */
    public void onEvent(Consumer<MsmlEvent> listener) {
        if (listener != null) {
            events().subscribe(listener);
        }
    }
}
