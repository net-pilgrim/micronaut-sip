package net.pilgrim.sip.transport.strategy;

import io.micronaut.core.annotation.Nullable;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import jakarta.inject.Singleton;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.transport.SipDatagramCodec;
import net.pilgrim.sip.transport.SipServerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Concrete strategy for SIP over UDP datagram transport (RFC 3261).
 */
@Singleton
public class UdpTransportStrategy implements SipTransportStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(UdpTransportStrategy.class);

    private EventLoopGroup udpGroup;
    private Channel udpChannel;
    private InetSocketAddress boundAddress;
    private volatile boolean running = false;

    @Override
    public SipTransport getTransport() {
        return SipTransport.UDP;
    }

    @Override
    public boolean isEnabled(SipServerConfiguration configuration) {
        return configuration.isUdpEnabled();
    }

    @Override
    public synchronized void startServer(ServerTransportContext context) throws InterruptedException {
        if (running) {
            return;
        }

        SipServerConfiguration config = context.configuration();
        udpGroup = new NioEventLoopGroup(2);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(udpGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)
                .option(ChannelOption.SO_SNDBUF, 4 * 1024 * 1024)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast("sipCodec", new SipDatagramCodec(context.parser(), context.encoder()));
                        p.addLast("sipHandler", new SipServerHandler(context.dispatcher(), context.responseRouter()));
                    }
                });

        String host = config.getUdpHost();
        InetSocketAddress bindTarget = "0.0.0.0".equals(host)
                ? new InetSocketAddress(config.getUdpPort())
                : new InetSocketAddress(host, config.getUdpPort());

        ChannelFuture future = bootstrap.bind(bindTarget).sync();
        udpChannel = future.channel();
        boundAddress = (InetSocketAddress) udpChannel.localAddress();
        running = true;
        context.metrics().setTransportActive("udp", true);
        LOG.info("SIP UDP Server listening on {}", boundAddress);
    }

    @Override
    public synchronized void stopServer() {
        if (!running && udpGroup == null && udpChannel == null) {
            return;
        }
        running = false;
        if (udpChannel != null) {
            try {
                udpChannel.close().syncUninterruptibly();
            } catch (Exception ignored) {}
            udpChannel = null;
        }
        if (udpGroup != null) {
            udpGroup.shutdownGracefully();
            udpGroup = null;
        }
        boundAddress = null;
        LOG.info("SIP UDP Server stopped.");
    }

    @Override
    public boolean isRunning() {
        return running && udpChannel != null && udpChannel.isActive();
    }

    @Override
    public InetSocketAddress getBoundAddress() {
        return boundAddress;
    }

    @Override
    public int getPort(SipServerConfiguration configuration) {
        return boundAddress != null ? boundAddress.getPort() : configuration.getUdpPort();
    }

    @Override
    public ChannelFuture sendServerMessage(SipMessage message, InetSocketAddress destination) {
        if (!running || udpChannel == null) {
            throw new IllegalStateException("SIP UDP server is not running");
        }
        message.setTransport(SipTransport.UDP);
        message.setRemoteAddress(destination);
        return udpChannel.writeAndFlush(message);
    }

    @Override
    public ChannelFuture sendClientStreamMessage(ClientTransportContext context, SipMessage message, InetSocketAddress destination) {
        throw new UnsupportedOperationException("UDP does not use stream client messaging; use sendServerMessage");
    }

    @Override
    public ChannelInitializer<SocketChannel> createClientChannelInitializer(ClientTransportContext context,
                                                                            InetSocketAddress destination,
                                                                            ChannelInboundHandler clientHandler) {
        throw new UnsupportedOperationException("UDP does not use stream socket channel initializers");
    }
}
