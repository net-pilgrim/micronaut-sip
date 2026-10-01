package net.pilgrim.sip.transport;

import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.parser.SipEncoder;
import net.pilgrim.sip.parser.SipParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Netty codec that converts between UDP DatagramPacket and RFC 3261 SipMessage.
 */
public class SipDatagramCodec extends MessageToMessageCodec<DatagramPacket, SipMessage> {

    private static final Logger LOG = LoggerFactory.getLogger(SipDatagramCodec.class);

    private final SipParser parser;
    private final SipEncoder encoder;

    public SipDatagramCodec(SipParser parser, SipEncoder encoder) {
        this.parser = parser;
        this.encoder = encoder;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket packet, List<Object> out) {
        try {
            ByteBuf content = packet.content();
            byte[] bytes = ByteBufUtil.getBytes(content);
            InetSocketAddress sender = packet.sender();

            SipMessage sipMessage = parser.parse(bytes, sender);
            out.add(sipMessage);
        } catch (Exception e) {
            LOG.warn("Failed to decode incoming SIP datagram from {}: {}", packet.sender(), e.getMessage());
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, SipMessage msg, List<Object> out) {
        try {
            byte[] bytes = encoder.encode(msg);
            InetSocketAddress recipient = msg.getRemoteAddress();
            if (recipient == null) {
                LOG.error("Cannot encode SIP message: recipient remote address is null");
                return;
            }

            DatagramPacket packet = new DatagramPacket(Unpooled.wrappedBuffer(bytes), recipient);
            out.add(packet);
        } catch (Exception e) {
            LOG.error("Failed to encode outgoing SIP message: {}", e.getMessage(), e);
        }
    }
}
