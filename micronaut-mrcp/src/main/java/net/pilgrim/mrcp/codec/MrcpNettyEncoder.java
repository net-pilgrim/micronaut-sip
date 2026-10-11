package net.pilgrim.mrcp.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import net.pilgrim.mrcp.message.MrcpMessage;

/**
 * Netty {@link MessageToByteEncoder} for serializing {@link MrcpMessage} instances over TCP.
 */
public class MrcpNettyEncoder extends MessageToByteEncoder<MrcpMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, MrcpMessage msg, ByteBuf out) {
        if (msg == null) return;
        byte[] bytes = MrcpMessageSerializer.serialize(msg);
        out.writeBytes(bytes);
    }
}
