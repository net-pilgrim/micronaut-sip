package net.pilgrim.mrcp.resource;

import net.pilgrim.mrcp.message.MrcpEvent;
import net.pilgrim.mrcp.message.MrcpRequest;
import net.pilgrim.mrcp.message.MrcpResponse;
import reactor.core.publisher.Mono;

import java.io.Closeable;
import java.util.function.Consumer;

/**
 * Common lifecycle interface for an active MRCPv2 resource channel (RFC 6787 §4).
 */
public interface MrcpResource extends Closeable {

    /**
     * Unique channel identifier {@code <id>@<resource-type>} for this resource.
     */
    MrcpChannelId getChannelId();

    /**
     * Type of MRCP resource (speechsynth, speechrecog, etc.).
     */
    MrcpResourceType getResourceType();

    /**
     * Processes an incoming MRCP request targeted at this resource channel.
     *
     * @param request   incoming request message
     * @param eventSink callback to emit asynchronous MRCP events to the client
     * @return Mono emitting immediate response (e.g. 200 COMPLETE or 202 IN-PROGRESS)
     */
    Mono<MrcpResponse> handleRequest(MrcpRequest request, Consumer<MrcpEvent> eventSink);

    @Override
    void close();
}
