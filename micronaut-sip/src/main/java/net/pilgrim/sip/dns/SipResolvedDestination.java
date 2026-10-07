package net.pilgrim.sip.dns;

import net.pilgrim.sip.model.SipTransport;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Resolved target endpoint resulting from RFC 3263 DNS resolution (NAPTR -&gt; SRV -&gt; A/AAAA).
 */
public record SipResolvedDestination(
        InetSocketAddress address,
        SipTransport transport,
        int priority,
        int weight,
        long ttl
) implements Comparable<SipResolvedDestination> {

    public SipResolvedDestination {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(transport, "transport");
    }

    public SipResolvedDestination(InetSocketAddress address, SipTransport transport) {
        this(address, transport, 0, 0, 60);
    }

    public int port() {
        return address.getPort();
    }

    @Override
    public int compareTo(SipResolvedDestination other) {
        if (other == null) return -1;
        // Lower priority first (RFC 2782)
        int cmp = Integer.compare(this.priority, other.priority);
        if (cmp != 0) return cmp;
        // Higher weight first within same priority
        return Integer.compare(other.weight, this.weight);
    }
}
