package net.pilgrim.sip.transport;

import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.parser.SipEncoder;
import net.pilgrim.sip.parser.SipParser;
import net.pilgrim.sip.router.SipDispatcher;
import io.micronaut.context.annotation.Context;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.sip.metrics.SipMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Reactive Netty-based SIP Server supporting both UDP (RFC 3261) and TCP (RFC 3261 Section 18.1.1).
 */
@Singleton
@Context
public class SipNettyServer {

    private static final Logger LOG = LoggerFactory.getLogger(SipNettyServer.class);

    private final SipServerConfiguration configuration;
    private final SipDispatcher dispatcher;
    private final SipResponseRouter responseRouter;
    private final SipMetrics metrics;
    private final SipParser parser;
    private final SipEncoder encoder = new SipEncoder();

    // UDP
    private EventLoopGroup udpGroup;
    private Channel udpChannel;
    private InetSocketAddress udpBoundAddress;

    // TCP
    private EventLoopGroup tcpBossGroup;
    private EventLoopGroup tcpWorkerGroup;
    private Channel tcpChannel;
    private InetSocketAddress tcpBoundAddress;

    private volatile boolean running = false;

    public SipNettyServer(SipServerConfiguration configuration,
                          SipDispatcher dispatcher,
                          SipResponseRouter responseRouter) {
        this(configuration, dispatcher, responseRouter, null);
    }

    @Inject
    public SipNettyServer(SipServerConfiguration configuration,
                          SipDispatcher dispatcher,
                          SipResponseRouter responseRouter,
                          @Nullable SipMetrics metrics) {
        this.configuration = configuration;
        this.dispatcher = dispatcher;
        this.responseRouter = responseRouter;
        this.metrics = metrics != null ? metrics : SipMetrics.NOOP;
        this.parser = new SipParser(
                configuration.getMaxMessageSizeBytes(),
                configuration.getMaxHeaderCount(),
                configuration.getMaxHeaderSizeBytes()
        );
    }

    @PostConstruct
    public synchronized void start() {
        if (!configuration.isEnabled()) {
            LOG.info("SIP Server is disabled by configuration.");
            return;
        }

        if (running) {
            return;
        }

        try {
            // 1. Start UDP Server if enabled
            if (configuration.isUdpEnabled()) {
                startUdp();
            }

            // 2. Start TCP Server if enabled
            if (configuration.isTcpEnabled()) {
                startTcp();
            }

            running = true;
        } catch (Exception e) {
            LOG.error("Failed to start SIP Server: {}", e.getMessage(), e);
            stop();
            throw new RuntimeException("Could not start SIP server", e);
        }
    }

    private void startUdp() throws InterruptedException {
        udpGroup = new NioEventLoopGroup(2);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(udpGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast("sipCodec", new SipDatagramCodec(parser, encoder));
                        p.addLast("sipHandler", new SipServerHandler(dispatcher, responseRouter));
                    }
                });

        String udpHost = configuration.getUdpHost();
        InetSocketAddress bindTarget = "0.0.0.0".equals(udpHost)
                ? new InetSocketAddress(configuration.getUdpPort())
                : new InetSocketAddress(udpHost, configuration.getUdpPort());

        ChannelFuture future = bootstrap.bind(bindTarget).sync();
        udpChannel = future.channel();
        udpBoundAddress = (InetSocketAddress) udpChannel.localAddress();
        metrics.setTransportActive("udp", true);
        LOG.info("SIP UDP Server listening on {}", udpBoundAddress);
    }

    private void startTcp() throws InterruptedException {
        tcpBossGroup = new NioEventLoopGroup(1);
        tcpWorkerGroup = new NioEventLoopGroup(2);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(tcpBossGroup, tcpWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast("streamDecoder", new SipStreamFrameDecoder(parser));
                        p.addLast("streamEncoder", new SipStreamEncoder(encoder));
                        p.addLast("tcpHandler", new SipTcpServerHandler(dispatcher, responseRouter));
                    }
                });

        int tcpPort = configuration.getTcpPort();
        String tcpHost = configuration.getTcpHost();
        InetSocketAddress bindTarget = "0.0.0.0".equals(tcpHost)
                ? new InetSocketAddress(tcpPort)
                : new InetSocketAddress(tcpHost, tcpPort);

        ChannelFuture future = bootstrap.bind(bindTarget).sync();
        tcpChannel = future.channel();
        tcpBoundAddress = (InetSocketAddress) tcpChannel.localAddress();
        metrics.setTransportActive("tcp", true);
        LOG.info("SIP TCP Server listening on {}", tcpBoundAddress);
    }

    @PreDestroy
    public synchronized void stop() {
        if (!running && udpGroup == null && tcpBossGroup == null) {
            return;
        }
        LOG.info("Shutting down SIP Netty Server...");
        running = false;
        metrics.setTransportActive("udp", false);
        metrics.setTransportActive("tcp", false);

        // Stop UDP
        if (udpChannel != null) {
            try { udpChannel.close().syncUninterruptibly(); } catch (Exception ignored) {}
            udpChannel = null;
        }
        if (udpGroup != null) {
            udpGroup.shutdownGracefully();
            udpGroup = null;
        }

        // Stop TCP
        if (tcpChannel != null) {
            try { tcpChannel.close().syncUninterruptibly(); } catch (Exception ignored) {}
            tcpChannel = null;
        }
        if (tcpBossGroup != null) {
            tcpBossGroup.shutdownGracefully();
            tcpBossGroup = null;
        }
        if (tcpWorkerGroup != null) {
            tcpWorkerGroup.shutdownGracefully();
            tcpWorkerGroup = null;
        }

        LOG.info("SIP Netty Server stopped.");
    }

    public ChannelFuture send(SipMessage message, InetSocketAddress destination) {
        SipTransport transport = message != null && message.getTransport() != null
                ? message.getTransport()
                : SipTransport.UDP;
        if (transport == SipTransport.TCP) {
            return sendTcp(message, destination);
        }
        return sendUdp(message, destination);
    }

    public ChannelFuture sendUdp(SipMessage message, InetSocketAddress destination) {
        if (!running || udpChannel == null) {
            throw new IllegalStateException("SIP UDP server is not running");
        }
        message.setTransport(SipTransport.UDP);
        message.setRemoteAddress(destination);
        return udpChannel.writeAndFlush(message);
    }

    public ChannelFuture sendTcp(SipMessage message, InetSocketAddress destination) {
        if (!running || tcpWorkerGroup == null) {
            throw new IllegalStateException("SIP TCP server is not running");
        }
        if (destination == null) {
            throw new IllegalArgumentException("SIP TCP destination must not be null");
        }

        message.setTransport(SipTransport.TCP);
        message.setRemoteAddress(destination);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(tcpWorkerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("streamEncoder", new SipStreamEncoder(encoder));
                    }
                });

        ChannelFuture connectFuture = bootstrap.connect(destination);
        connectFuture.addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                LOG.error("Failed opening outbound SIP TCP connection to {}: {}", destination, future.cause().getMessage(), future.cause());
                return;
            }
            Channel channel = future.channel();
            channel.writeAndFlush(message).addListener(writeFuture -> channel.close());
        });

        return connectFuture;
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return getUdpPort();
    }

    public int getUdpPort() {
        return udpBoundAddress != null ? udpBoundAddress.getPort() : configuration.getUdpPort();
    }

    public int getTcpPort() {
        return tcpBoundAddress != null ? tcpBoundAddress.getPort() : configuration.getTcpPort();
    }

    public InetSocketAddress getBoundAddress() {
        return getUdpBoundAddress();
    }

    public InetSocketAddress getUdpBoundAddress() {
        return udpBoundAddress;
    }

    public InetSocketAddress getTcpBoundAddress() {
        return tcpBoundAddress;
    }

    public SipParser getParser() {
        return parser;
    }

    public SipEncoder getEncoder() {
        return encoder;
    }

    public SipServerConfiguration getConfiguration() {
        return configuration;
    }

    public String getAdvertisedIp() {
        return configuration.resolveAdvertisedIp();
    }
}
