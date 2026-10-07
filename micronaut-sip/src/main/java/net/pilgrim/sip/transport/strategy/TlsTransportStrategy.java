package net.pilgrim.sip.transport.strategy;

import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import jakarta.inject.Singleton;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.security.SipSslContextFactory;
import net.pilgrim.sip.transport.SipStreamEncoder;
import net.pilgrim.sip.transport.SipStreamFrameDecoder;

import java.net.InetSocketAddress;

/**
 * Concrete strategy for SIP over TLS transport (RFC 3261 SIPS / RFC 5630).
 */
@Singleton
public class TlsTransportStrategy extends AbstractStreamTransportStrategy {

    private SslContext serverSslContext;
    private SslContext outboundClientSslContext;

    @Override
    public SipTransport getTransport() {
        return SipTransport.TLS;
    }

    @Override
    public boolean isEnabled(SipServerConfiguration configuration) {
        return configuration.isTlsEnabled();
    }

    @Override
    protected String getConfiguredHost(SipServerConfiguration config) {
        return config.getTlsHost();
    }

    @Override
    protected int getConfiguredPort(SipServerConfiguration config) {
        return config.getTlsPort();
    }

    @Override
    protected int getWorkerThreads(SipServerConfiguration config) {
        return Math.max(2, config.getTlsWorkerThreads());
    }

    @Override
    public synchronized void startServer(ServerTransportContext context) throws InterruptedException {
        this.serverSslContext = SipSslContextFactory.createServerSslContext(context.configuration());
        this.outboundClientSslContext = SipSslContextFactory.createClientSslContext(context.configuration());
        super.startServer(context);
    }

    @Override
    public synchronized void stopServer() {
        super.stopServer();
        this.serverSslContext = null;
        this.outboundClientSslContext = null;
    }

    @Override
    protected void configureServerChannel(SocketChannel ch, ServerTransportContext context) {
        if (serverSslContext != null) {
            ch.pipeline().addLast("ssl", serverSslContext.newHandler(ch.alloc()));
        }
    }

    @Override
    protected void configureOutboundChannel(SocketChannel ch, InetSocketAddress destination) {
        if (outboundClientSslContext != null && destination != null) {
            ch.pipeline().addLast("ssl", outboundClientSslContext.newHandler(
                    ch.alloc(), destination.getHostString(), destination.getPort()));
        }
    }

    @Override
    protected void configureOutboundClientChannel(SocketChannel ch, ClientTransportContext context, InetSocketAddress destination) {
        if (context.clientSslContext() != null && destination != null) {
            ch.pipeline().addLast("ssl", context.clientSslContext().newHandler(
                    ch.alloc(), destination.getHostString(), destination.getPort()));
        }
    }

    @Override
    public ChannelInitializer<SocketChannel> createClientChannelInitializer(ClientTransportContext context,
                                                                            InetSocketAddress destination,
                                                                            ChannelInboundHandler clientHandler) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                if (context.clientSslContext() != null && destination != null) {
                    ch.pipeline().addLast("ssl", context.clientSslContext().newHandler(
                            ch.alloc(), destination.getHostString(), destination.getPort()));
                }
                ch.pipeline().addLast("streamDecoder", new SipStreamFrameDecoder(context.parser()));
                ch.pipeline().addLast("streamEncoder", new SipStreamEncoder(context.encoder()));
                ch.pipeline().addLast("clientHandler", clientHandler);
            }
        };
    }
}
