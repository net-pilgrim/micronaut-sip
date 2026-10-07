package net.pilgrim.sip.transport.strategy;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipTransport;

import java.util.*;

/**
 * Registry holding and resolving active {@link SipTransportStrategy} implementations.
 */
@Singleton
public class SipTransportRegistry {

    private final Map<SipTransport, SipTransportStrategy> strategies = new EnumMap<>(SipTransport.class);

    public SipTransportRegistry() {
        this(List.of(
                new UdpTransportStrategy(),
                new TcpTransportStrategy(),
                new TlsTransportStrategy()
        ));
    }

    @Inject
    public SipTransportRegistry(@Nullable List<SipTransportStrategy> strategyList) {
        if (strategyList != null && !strategyList.isEmpty()) {
            for (SipTransportStrategy s : strategyList) {
                strategies.put(s.getTransport(), s);
            }
        }
        // Ensure defaults exist if not provided
        strategies.putIfAbsent(SipTransport.UDP, new UdpTransportStrategy());
        strategies.putIfAbsent(SipTransport.TCP, new TcpTransportStrategy());
        strategies.putIfAbsent(SipTransport.TLS, new TlsTransportStrategy());
    }

    public static SipTransportRegistry withDefaults() {
        return new SipTransportRegistry();
    }

    /**
     * Resolves the strategy for a transport type.
     */
    public SipTransportStrategy get(SipTransport transport) {
        if (transport == null) {
            transport = SipTransport.UDP;
        }
        SipTransportStrategy strategy = strategies.get(transport);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported SIP transport: " + transport);
        }
        return strategy;
    }

    /**
     * Finds the strategy explicitly registered for a transport type, if present.
     */
    public Optional<SipTransportStrategy> getStrategy(SipTransport transport) {
        return Optional.ofNullable(strategies.get(transport));
    }

    /**
     * Resolves the strategy matching an incoming request.
     */
    public SipTransportStrategy get(SipRequest request) {
        SipTransport transport = (request != null && request.getTransport() != null)
                ? request.getTransport()
                : SipTransport.UDP;
        return get(transport);
    }

    /**
     * Returns all registered strategies.
     */
    public Collection<SipTransportStrategy> getAll() {
        return Collections.unmodifiableCollection(strategies.values());
    }

    /**
     * Resolves the appropriate server listening port for a request's transport.
     */
    public int resolveServerPort(SipRequest request, SipServerConfiguration configuration) {
        return get(request).getPort(configuration);
    }

    /**
     * Formats a Contact URI matching the request's transport.
     */
    public String formatContactUri(SipRequest request, String advertisedIp, int port) {
        return formatContactUri(request, advertisedIp, port, null);
    }

    /**
     * Formats a Contact URI matching the request's transport with an optional user part.
     */
    public String formatContactUri(SipRequest request, String advertisedIp, int port, String user) {
        return get(request).formatContactUri(advertisedIp, port, user);
    }
}
