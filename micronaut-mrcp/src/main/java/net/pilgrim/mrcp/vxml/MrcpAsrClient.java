package net.pilgrim.mrcp.vxml;

import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import net.pilgrim.mrcp.client.MrcpClient;
import net.pilgrim.mrcp.message.*;
import net.pilgrim.mrcp.mistral.MistralAudioClient;
import net.pilgrim.mrcp.mistral.MistralRecognitionResult;
import net.pilgrim.mrcp.resource.MrcpChannelId;
import net.pilgrim.vxml.speech.AsrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VoiceXML {@link AsrClient} adapter bridging VoiceXML field speech input recognition
 * to Mistral Audio and MRCPv2 speech recognizer resources.
 */
@Singleton
@Primary
@Named("mrcp")
@Requires(classes = AsrClient.class)
public class MrcpAsrClient implements AsrClient {

    private static final Logger LOG = LoggerFactory.getLogger(MrcpAsrClient.class);
    private static final Pattern NLSML_INSTANCE_PATTERN = Pattern.compile("<instance>([^<]+)</instance>");
    private static final Pattern NLSML_INPUT_PATTERN = Pattern.compile("<input[^>]*>([^<]+)</input>");

    private final MistralAudioClient mistralClient;
    private final MrcpClient mrcpClient;
    private final MrcpChannelId channelId;

    @Inject
    public MrcpAsrClient(MistralAudioClient mistralClient) {
        this(mistralClient, null, null);
    }

    public MrcpAsrClient(MistralAudioClient mistralClient, MrcpClient mrcpClient, MrcpChannelId channelId) {
        this.mistralClient = mistralClient;
        this.mrcpClient = mrcpClient;
        this.channelId = channelId;
    }

    @Override
    public Mono<String> recognize(byte[] pcmAudio, String grammar) {
        // If connected via MRCP network client, perform recognition via MRCP protocol
        if (mrcpClient != null && mrcpClient.isConnected() && channelId != null) {
            return recognizeViaMrcp(pcmAudio, grammar);
        }

        // Direct in-process bridging to Mistral Audio client
        if (mistralClient != null) {
            return mistralClient.recognize(pcmAudio, grammar, null)
                    .map(MistralRecognitionResult::getText);
        }

        return Mono.empty();
    }

    private Mono<String> recognizeViaMrcp(byte[] pcmAudio, String grammar) {
        long reqId = mrcpClient.nextRequestId();
        MrcpRequest req = new MrcpRequest(MrcpMethod.RECOGNIZE, reqId);
        req.getHeaders().setChannelIdentifier(channelId);
        if (grammar != null && !grammar.isBlank()) {
            req.setBody(grammar, "application/srgs+xml");
        }

        var eventMono = mrcpClient.events(reqId)
                .filter(e -> e.getEvent() == MrcpEventName.RECOGNITION_COMPLETE)
                .next()
                .timeout(Duration.ofSeconds(10))
                .map(event -> {
                    String body = event.getBodyAsString();
                    if (body != null) {
                        Matcher m = NLSML_INSTANCE_PATTERN.matcher(body);
                        if (m.find()) return m.group(1).trim();
                        Matcher m2 = NLSML_INPUT_PATTERN.matcher(body);
                        if (m2.find()) return m2.group(1).trim();
                    }
                    return "";
                });

        return mrcpClient.sendRequest(req)
                .flatMap(resp -> {
                    if (resp.getStatusCode() == MrcpStatusCode.IN_PROGRESS || resp.getStatusCode() == MrcpStatusCode.SUCCESS) {
                        return eventMono;
                    }
                    return Mono.just("");
                });
    }
}
