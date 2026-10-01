package net.pilgrim.sip.transport;

import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.router.SipDispatcher;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty inbound handler for active TCP connections.
 * Forwards requests to SipDispatcher and returns responses over the same connection.
 */
public class SipTcpServerHandler extends SimpleChannelInboundHandler<SipMessage> {

    private static final Logger LOG = LoggerFactory.getLogger(SipTcpServerHandler.class);

    private final SipDispatcher dispatcher;
    private final SipResponseRouter responseRouter;

    public SipTcpServerHandler(SipDispatcher dispatcher, SipResponseRouter responseRouter) {
        this.dispatcher = dispatcher;
        this.responseRouter = responseRouter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, SipMessage msg) {
        if (msg instanceof SipRequest request) {
            SipMessageSender sender = new SipMessageSender() {
                @Override
                public void sendMessage(SipMessage message) {
                    // In RFC 3261 Section 18.2.2, responses over reliable transport (TCP)
                    // MUST be sent over the same connection that was used to receive the request.
                    ctx.channel().writeAndFlush(message);
                }
            };
            dispatcher.dispatch(request, sender);
        } else if (msg instanceof SipResponse response) {
            responseRouter.handleResponse(response);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        LOG.debug("New incoming SIP TCP connection from {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        LOG.debug("SIP TCP connection closed from {}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.error("Exception on SIP TCP connection {}: {}", ctx.channel().remoteAddress(), cause.getMessage(), cause);
        ctx.close();
    }
}
