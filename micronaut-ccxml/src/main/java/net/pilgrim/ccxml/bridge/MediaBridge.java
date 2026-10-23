package net.pilgrim.ccxml.bridge;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Interface bridging CCXML media control to media mixing engines (e.g. micronaut-msml, micronaut-rtp).
 */
public interface MediaBridge {

    /**
     * Allocates an audio conference mixer.
     *
     * @param confName human-readable conference name or room ID
     * @param attributes optional conference configuration attributes
     * @return Mono emitting the newly created conferenceId
     */
    Mono<String> createConference(String confName, Map<String, Object> attributes);

    /**
     * Destroys an audio conference mixer and cleans up associated streams.
     *
     * @param conferenceId the conference identifier
     * @return Mono completing when destroyed
     */
    Mono<Void> destroyConference(String conferenceId);

    /**
     * Joins two audio endpoints (connection-to-connection or connection-to-conference).
     *
     * @param id1 first endpoint ID
     * @param id2 second endpoint ID
     * @param duplex full-duplex or half-duplex
     * @return Mono completing when joined
     */
    Mono<Void> join(String id1, String id2, DuplexMode duplex);

    /**
     * Unjoins two audio endpoints.
     *
     * @param id1 first endpoint ID
     * @param id2 second endpoint ID
     * @return Mono completing when unjoined
     */
    Mono<Void> unjoin(String id1, String id2);
}
