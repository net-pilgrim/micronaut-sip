package net.pilgrim.sip.transport.strategy;

import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import jakarta.inject.Singleton;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.transport.SipStreamEncoder;
import net.pilgrim.sip.transport.SipStreamFrameDecoder;

import java.net.InetSocketAddress;

/**
 * Concrete strategy for SIP over TCP stream transport (RFC 3261).
 */
@Singleton
public class TcpTransportStrategy extends AbstractStreamTransportStrategy {

    @Override
    public SipTransport getTransport() {
        return SipTransport.TCP;
    }

    @Override
    public boolean isEnabled(SipServerConfiguration configuration) {
        return configuration.isTcpEnabled();
    }

    @Override
    protected void configureServerChannel(SocketChannel ch, ServerTransportContext context) {
        // Plain TCP has no TLS layer
    }

    @Override
    protected String getConfiguredHost(SipServerConfiguration config) {
        return config.getTcpHost();
    }

    @Override
    protected int getConfiguredPort(SipServerConfiguration config) {
        return config.getTcpPort();
    }

    @Override
    public ChannelInitializer<SocketChannel> createClientChannelInitializer(ClientTransportContext context,
                                                                            InetSocketAddress destination,
                                                                            ChannelInboundHandler clientHandler) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast("streamDecoder", new SipStreamFrameDecoder(context.parser()));
                ch.pipeline().addLast("streamEncoder", new SipStreamEncoder(context.encoder()));
                ch.pipeline().addLast("clientHandler", clientHandler);
            }
        };
    }
}
