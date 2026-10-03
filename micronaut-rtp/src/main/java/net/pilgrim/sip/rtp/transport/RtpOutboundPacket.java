package net.pilgrim.sip.rtp.transport;

import net.pilgrim.sip.rtp.RtpPacket;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Represents an outbound RTP packet destined for a remote address and port.
 */
public record RtpOutboundPacket(
        RtpPacket packet,
        InetSocketAddress destination
) {
    public RtpOutboundPacket {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(destination, "destination");
    }
}
