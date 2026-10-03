package net.pilgrim.sip.rtp.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import net.pilgrim.sip.rtp.RtpPacket;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Netty codec that decodes {@link DatagramPacket} into {@link RtpInboundPacket}
 * and encodes {@link RtpOutboundPacket} or {@link RtpPacket} into {@link DatagramPacket}.
 */
@ChannelHandler.Sharable
public class RtpDatagramCodec extends MessageToMessageCodec<DatagramPacket, Object> {

    private volatile InetSocketAddress defaultRemoteAddress;

    public RtpDatagramCodec() {
        this(null);
    }

    public RtpDatagramCodec(InetSocketAddress defaultRemoteAddress) {
        this.defaultRemoteAddress = defaultRemoteAddress;
    }

    public InetSocketAddress getDefaultRemoteAddress() {
        return defaultRemoteAddress;
    }

    public void setDefaultRemoteAddress(InetSocketAddress defaultRemoteAddress) {
        this.defaultRemoteAddress = defaultRemoteAddress;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) {
        ByteBuf content = msg.content();
        if (content.readableBytes() < 12) {
            return;
        }
        try {
            RtpPacket packet = RtpPacket.parse(content);
            out.add(new RtpInboundPacket(packet, msg.sender()));
        } catch (IllegalArgumentException ignored) {
            // Silently discard non-RTP or malformed UDP datagrams
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) {
        if (msg instanceof RtpOutboundPacket outbound) {
            ByteBuf buffer = ctx.alloc().buffer(12 + outbound.packet().getPayload().length);
            outbound.packet().encode(buffer);
            out.add(new DatagramPacket(buffer, outbound.destination()));
        } else if (msg instanceof RtpPacket packet) {
            InetSocketAddress target = defaultRemoteAddress;
            if (target == null) {
                throw new IllegalArgumentException(
                        "Cannot encode raw RtpPacket without destination address; use RtpOutboundPacket or set defaultRemoteAddress");
            }
            ByteBuf buffer = ctx.alloc().buffer(12 + packet.getPayload().length);
            packet.encode(buffer);
            out.add(new DatagramPacket(buffer, target));
        } else if (msg instanceof DatagramPacket dp) {
            out.add(dp.retain());
        }
    }
}
