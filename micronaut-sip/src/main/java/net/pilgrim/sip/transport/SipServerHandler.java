package net.pilgrim.sip.transport;

import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.router.SipDispatcher;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty channel handler that delegates incoming SIP messages to the SipDispatcher or SipResponseRouter.
 */
public class SipServerHandler extends SimpleChannelInboundHandler<SipMessage> {

    private static final Logger LOG = LoggerFactory.getLogger(SipServerHandler.class);

    private final SipDispatcher dispatcher;
    private final SipResponseRouter responseRouter;

    public SipServerHandler(SipDispatcher dispatcher, SipResponseRouter responseRouter) {
        this.dispatcher = dispatcher;
        this.responseRouter = responseRouter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, SipMessage msg) {
        Channel channel = ctx.channel();

        if (msg instanceof SipRequest request) {
            SipMessageSender sender = new SipMessageSender() {
                @Override
                public void sendMessage(SipMessage message) {
                    if (message.getRemoteAddress() == null) {
                        message.setRemoteAddress(request.getRemoteAddress());
                    }
                    channel.writeAndFlush(message);
                }
            };
            dispatcher.dispatch(request, sender);
        } else if (msg instanceof SipResponse response) {
            responseRouter.handleResponse(response);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.error("Netty exception in SIP channel handler: {}", cause.getMessage(), cause);
    }
}
