package net.pilgrim.sip.dns;

import net.pilgrim.sip.model.SipUri;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * RFC 3263 SIP Server Location Resolver.
 * Resolves SIP URIs to target IP address, port, and transport protocol.
 */
public interface SipDnsResolver {

    /**
     * Resolves all candidate destinations ordered by RFC 2782 priority and weight.
     */
    Mono<List<SipResolvedDestination>> resolve(SipUri uri);

    /**
     * Resolves the primary (highest priority) destination.
     */
    default Mono<SipResolvedDestination> resolvePrimary(SipUri uri) {
        return resolve(uri)
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0));
    }
}
