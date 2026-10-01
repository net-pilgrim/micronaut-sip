package net.pilgrim.sip.transport;

import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.parser.SipEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Netty encoder that serializes a SipMessage into a stream ByteBuf over TCP.
 */
public class SipStreamEncoder extends MessageToByteEncoder<SipMessage> {

    private final SipEncoder encoder;

    public SipStreamEncoder(SipEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, SipMessage msg, ByteBuf out) {
        byte[] bytes = encoder.encode(msg);
        out.writeBytes(bytes);
    }
}
