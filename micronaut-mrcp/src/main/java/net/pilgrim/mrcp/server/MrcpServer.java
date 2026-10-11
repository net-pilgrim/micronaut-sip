package net.pilgrim.mrcp.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import net.pilgrim.mrcp.codec.MrcpNettyDecoder;
import net.pilgrim.mrcp.codec.MrcpNettyEncoder;
import net.pilgrim.mrcp.message.*;
import net.pilgrim.mrcp.resource.MrcpChannelId;
import net.pilgrim.mrcp.resource.MrcpChannelManager;
import net.pilgrim.mrcp.resource.MrcpResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.net.InetSocketAddress;

/**
 * Netty TCP server hosting the MRCPv2 protocol control channel (RFC 6787).
 */
@Context
public class MrcpServer implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(MrcpServer.class);

    private final MrcpChannelManager channelManager;
    private final MrcpServerConfiguration configuration;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private int port = 0;

    public MrcpServer(MrcpChannelManager channelManager) {
        this(channelManager, new MrcpServerConfiguration());
    }

    @Inject
    public MrcpServer(MrcpChannelManager channelManager, @Nullable MrcpServerConfiguration configuration) {
        this.channelManager = channelManager;
        this.configuration = configuration != null ? configuration : new MrcpServerConfiguration();
    }

    @PostConstruct
    public synchronized void start() {
        if (!configuration.isEnabled()) {
            LOG.info("MRCP Server is disabled by configuration.");
            return;
        }
        start(configuration.getPort());
    }

    public synchronized void start(int bindPort) {
        if (serverChannel != null && serverChannel.isActive()) {
            return;
        }

        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .childOption(ChannelOption.SO_KEEPALIVE, true)
             .childOption(ChannelOption.TCP_NODELAY, true)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ChannelPipeline p = ch.pipeline();
                     p.addLast("decoder", new MrcpNettyDecoder());
                     p.addLast("encoder", new MrcpNettyEncoder());
                     p.addLast("handler", new MrcpServerHandler(channelManager));
                 }
             });

            ChannelFuture future = b.bind(bindPort).sync();
            this.serverChannel = future.channel();
            InetSocketAddress localAddr = (InetSocketAddress) serverChannel.localAddress();
            this.port = localAddr.getPort();
            LOG.info("MRCPv2 server listening on port {}", this.port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while starting MRCP server", e);
        }
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return serverChannel != null && serverChannel.isActive();
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (serverChannel != null) {
            serverChannel.close();
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
        channelManager.closeAll();
        LOG.info("MRCPv2 server stopped");
    }

    private static class MrcpServerHandler extends SimpleChannelInboundHandler<MrcpMessage> {

        private final MrcpChannelManager channelManager;

        MrcpServerHandler(MrcpChannelManager channelManager) {
            this.channelManager = channelManager;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, MrcpMessage msg) {
            if (!(msg instanceof MrcpRequest req)) {
                LOG.debug("Server received non-request message, ignoring: {}", msg);
                return;
            }

            MrcpChannelId channelId = req.getHeaders().getChannelIdentifier();
            if (channelId == null) {
                MrcpResponse err = req.createResponse(MrcpStatusCode.MANDATORY_HEADER_MISSING, MrcpRequestState.COMPLETE);
                ctx.writeAndFlush(err);
                return;
            }

            MrcpResource resource = channelManager.getOrCreateResource(channelId);
            if (resource == null) {
                MrcpResponse err = req.createResponse(MrcpStatusCode.RESOURCE_NOT_FOUND, MrcpRequestState.COMPLETE);
                ctx.writeAndFlush(err);
                return;
            }

            java.util.List<MrcpEvent> queuedEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.concurrent.atomic.AtomicBoolean responseSent = new java.util.concurrent.atomic.AtomicBoolean(false);

            resource.handleRequest(req, event -> {
                if (responseSent.get()) {
                    if (ctx.channel().isActive()) {
                        ctx.writeAndFlush(event);
                    }
                } else {
                    queuedEvents.add(event);
                }
            }).subscribe(response -> {
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(response).addListener(f -> {
                        responseSent.set(true);
                        for (MrcpEvent ev : queuedEvents) {
                            if (ctx.channel().isActive()) {
                                ctx.writeAndFlush(ev);
                            }
                        }
                        queuedEvents.clear();
                    });
                }
            }, error -> {
                LOG.error("Error processing MRCP request: {}", error.getMessage(), error);
                MrcpResponse err = req.createResponse(MrcpStatusCode.SERVER_INTERNAL_ERROR, MrcpRequestState.COMPLETE);
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(err);
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.warn("MRCP channel exception: {}", cause.getMessage());
            ctx.close();
        }
    }
}
