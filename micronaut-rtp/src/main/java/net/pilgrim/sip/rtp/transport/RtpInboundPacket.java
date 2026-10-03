package net.pilgrim.sip.rtp.transport;

import net.pilgrim.sip.rtp.RtpPacket;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents an inbound RTP packet paired with its source network address and arrival timestamp.
 */
public record RtpInboundPacket(
        RtpPacket packet,
        InetSocketAddress senderAddress,
        Instant receivedAt
) {
    public RtpInboundPacket {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(senderAddress, "senderAddress");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    public RtpInboundPacket(RtpPacket packet, InetSocketAddress senderAddress) {
        this(packet, senderAddress, Instant.now());
    }
}
