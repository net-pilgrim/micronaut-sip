package net.pilgrim.sip.transport.strategy;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.transport.SipStreamEncoder;
import net.pilgrim.sip.transport.SipStreamFrameDecoder;
import net.pilgrim.sip.transport.SipTcpServerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Base strategy for stream-oriented SIP transports (TCP, TLS).
 */
public abstract class AbstractStreamTransportStrategy implements SipTransportStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractStreamTransportStrategy.class);

    protected EventLoopGroup bossGroup;
    protected EventLoopGroup workerGroup;
    protected Channel serverChannel;
    protected InetSocketAddress boundAddress;
    protected volatile boolean running = false;

    /**
     * Hook to configure transport-specific channel handlers (e.g. SSL) before standard framing.
     */
    protected abstract void configureServerChannel(SocketChannel ch, ServerTransportContext context);

    /**
     * Resolves the configured host for this transport.
     */
    protected abstract String getConfiguredHost(SipServerConfiguration config);

    /**
     * Resolves the configured port for this transport.
     */
    protected abstract int getConfiguredPort(SipServerConfiguration config);

    /**
     * Resolves worker thread count.
     */
    protected int getWorkerThreads(SipServerConfiguration config) {
        return 2;
    }

    @Override
    public synchronized void startServer(ServerTransportContext context) throws InterruptedException {
        if (running) {
            return;
        }

        SipServerConfiguration config = context.configuration();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(getWorkerThreads(config));

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        configureServerChannel(ch, context);
                        ChannelPipeline p = ch.pipeline();
                        p.addLast("streamDecoder", new SipStreamFrameDecoder(context.parser()));
                        p.addLast("streamEncoder", new SipStreamEncoder(context.encoder()));
                        p.addLast(getTransport().name().toLowerCase() + "Handler",
                                new SipTcpServerHandler(context.dispatcher(), context.responseRouter()));
                    }
                });

        int port = getConfiguredPort(config);
        String host = getConfiguredHost(config);
        InetSocketAddress bindTarget = "0.0.0.0".equals(host)
                ? new InetSocketAddress(port)
                : new InetSocketAddress(host, port);

        ChannelFuture future = bootstrap.bind(bindTarget).sync();
        serverChannel = future.channel();
        boundAddress = (InetSocketAddress) serverChannel.localAddress();
        running = true;
        context.metrics().setTransportActive(getTransport().name().toLowerCase(), true);
        LOG.info("SIP {} Server listening on {}", getTransport(), boundAddress);
    }

    @Override
    public synchronized void stopServer() {
        if (!running && bossGroup == null && workerGroup == null && serverChannel == null) {
            return;
        }
        running = false;
        if (serverChannel != null) {
            try {
                serverChannel.close().syncUninterruptibly();
            } catch (Exception ignored) {}
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        boundAddress = null;
        LOG.info("SIP {} Server stopped.", getTransport());
    }

    @Override
    public boolean isRunning() {
        return running && serverChannel != null && serverChannel.isActive();
    }

    @Override
    public InetSocketAddress getBoundAddress() {
        return boundAddress;
    }

    @Override
    public int getPort(SipServerConfiguration configuration) {
        return boundAddress != null ? boundAddress.getPort() : getConfiguredPort(configuration);
    }

    @Override
    public ChannelFuture sendServerMessage(SipMessage message, InetSocketAddress destination) {
        if (!running || workerGroup == null) {
            throw new IllegalStateException("SIP " + getTransport() + " server is not running");
        }
        if (destination == null) {
            throw new IllegalArgumentException("SIP " + getTransport() + " destination must not be null");
        }

        message.setTransport(getTransport());
        message.setRemoteAddress(destination);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        configureOutboundChannel(ch, destination);
                        ch.pipeline().addLast("streamEncoder", new SipStreamEncoder(message instanceof SipMessage ? new net.pilgrim.sip.parser.SipEncoder() : null));
                    }
                });

        ChannelFuture connectFuture = bootstrap.connect(destination);
        connectFuture.addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                LOG.error("Failed opening outbound SIP {} connection to {}: {}",
                        getTransport(), destination, future.cause().getMessage(), future.cause());
                return;
            }
            Channel channel = future.channel();
            channel.writeAndFlush(message).addListener(writeFuture -> channel.close());
        });

        return connectFuture;
    }

    protected void configureOutboundChannel(SocketChannel ch, InetSocketAddress destination) {
        // Default no-op for plain TCP, overridden in TLS for SSL
    }

    @Override
    public ChannelFuture sendClientStreamMessage(ClientTransportContext context, SipMessage message, InetSocketAddress destination) {
        if (destination == null) {
            throw new IllegalArgumentException("SIP " + getTransport() + " destination must not be null");
        }

        message.setTransport(getTransport());
        message.setRemoteAddress(destination);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(context.clientWorkerGroup())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        configureOutboundClientChannel(ch, context, destination);
                        ch.pipeline().addLast("streamEncoder", new SipStreamEncoder(context.encoder()));
                    }
                });

        ChannelFuture connectFuture = bootstrap.connect(destination);
        connectFuture.addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                LOG.error("Failed opening client SIP {} connection to {}: {}",
                        getTransport(), destination, future.cause().getMessage(), future.cause());
                return;
            }
            Channel channel = future.channel();
            channel.writeAndFlush(message).addListener(writeFuture -> channel.close());
        });

        return connectFuture;
    }

    protected void configureOutboundClientChannel(SocketChannel ch, ClientTransportContext context, InetSocketAddress destination) {
        // Default no-op for plain TCP, overridden in TLS
    }
}
