package net.pilgrim.sip.rtp.media;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.sip.rtp.codec.RtpCodec;
import net.pilgrim.sip.rtp.codec.RtpCodecRegistry;
import net.pilgrim.sip.rtp.config.RtpConfiguration;
import net.pilgrim.sip.rtp.transport.RtpDatagramCodec;
import net.pilgrim.sip.rtp.transport.RtpInboundPacket;
import reactor.core.publisher.Sinks;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Top-level media manager coordinating high-concurrency RTP sessions.
 * Backed by a dedicated Netty event loop group for media traffic isolation.
 */
@Singleton
public class RtpMediaManager implements Closeable {

    private final RtpConfiguration configuration;
    private final MediaPortManager portManager;
    private final EventLoopGroup workerGroup;
    private final boolean ownsWorkerGroup;
    private final RtpCodecRegistry codecRegistry;
    private final ConcurrentMap<String, RtpMediaSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RtpMediaManager() {
        this(new RtpConfiguration(), null, null);
    }

    @Inject
    public RtpMediaManager(RtpConfiguration configuration) {
        this(configuration, null, null);
    }

    public RtpMediaManager(RtpConfiguration configuration,
                           MediaPortManager portManager,
                           EventLoopGroup workerGroup) {
        this.configuration = configuration != null ? configuration : new RtpConfiguration();
        this.portManager = portManager != null
                ? portManager
                : new MediaPortManager(this.configuration.getPortRangeStart(), this.configuration.getPortRangeEnd());
        this.codecRegistry = RtpCodecRegistry.withG711Defaults();

        if (workerGroup != null) {
            this.workerGroup = workerGroup;
            this.ownsWorkerGroup = false;
        } else {
            int threads = Math.max(2, this.configuration.getWorkerThreads());
            this.workerGroup = new NioEventLoopGroup(threads, new DefaultThreadFactory("rtp-worker", true));
            this.ownsWorkerGroup = true;
        }
    }

    /**
     * Creates and binds an RTP media session for the given SIP Call-ID.
     *
     * @param callId SIP Call-ID
     * @return active RTP media session
     */
    public RtpMediaSession createSession(String callId) throws IOException {
        return createSession(callId, null, null);
    }

    /**
     * Creates and binds an RTP media session for the given Call-ID with initial target and codec.
     */
    public RtpMediaSession createSession(String callId,
                                         InetSocketAddress remoteAddress,
                                         RtpCodec codec) throws IOException {
        Objects.requireNonNull(callId, "callId");
        if (closed.get()) {
            throw new IllegalStateException("RtpMediaManager is closed");
        }

        // Return existing session if already created for this callId
        RtpMediaSession existing = sessions.get(callId);
        if (existing != null && !existing.isClosed()) {
            if (remoteAddress != null) {
                existing.setRemoteAddress(remoteAddress);
            }
            if (codec != null) {
                existing.setCodec(codec);
            }
            return existing;
        }

        int localPort = portManager.allocatePort();
        Sinks.Many<RtpInboundPacket> incomingSink = Sinks.many().multicast().onBackpressureBuffer(2048, false);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_RCVBUF, 1024 * 1024)
                .option(ChannelOption.SO_SNDBUF, 1024 * 1024)
                .handler(new io.netty.channel.ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) {
                        ch.pipeline().addLast("codec", new RtpDatagramCodec(remoteAddress));
                        ch.pipeline().addLast("handler", new SimpleChannelInboundHandler<RtpInboundPacket>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, RtpInboundPacket msg) {
                                RtpMediaSession session = sessions.get(callId);
                                if (session != null) {
                                    session.onInboundPacket(msg);
                                }
                            }
                        });
                    }
                });

        try {
            InetAddress bindAddr = InetAddress.getByName(configuration.getBindAddress());
            ChannelFuture future = bootstrap.bind(new InetSocketAddress(bindAddr, localPort)).sync();
            Channel channel = future.channel();

            RtpMediaSession session = new RtpMediaSession(
                    callId,
                    localPort,
                    channel,
                    portManager,
                    incomingSink,
                    codec,
                    remoteAddress
            );
            sessions.put(callId, session);
            return session;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            portManager.releasePort(localPort);
            throw new IOException("Interrupted while binding RTP port " + localPort + " for Call-ID: " + callId, e);
        } catch (Exception e) {
            portManager.releasePort(localPort);
            throw new IOException("Failed to bind RTP port " + localPort + " for Call-ID: " + callId + ": " + e.getMessage(), e);
        }
    }

    public Optional<RtpMediaSession> findSession(String callId) {
        if (callId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(callId)).filter(s -> !s.isClosed());
    }

    public RtpMediaSession getOrCreateSession(String callId) throws IOException {
        Optional<RtpMediaSession> opt = findSession(callId);
        if (opt.isPresent()) {
            return opt.get();
        }
        return createSession(callId);
    }

    public void terminateSession(String callId) {
        if (callId == null) {
            return;
        }
        RtpMediaSession session = sessions.remove(callId);
        if (session != null) {
            session.close();
        }
    }

    public int getActiveSessionCount() {
        return (int) sessions.values().stream().filter(s -> !s.isClosed()).count();
    }

    public MediaPortManager getPortManager() {
        return portManager;
    }

    public RtpCodecRegistry getCodecRegistry() {
        return codecRegistry;
    }

    public RtpConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    @PreDestroy
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (RtpMediaSession session : sessions.values()) {
                session.close();
            }
            sessions.clear();
            if (ownsWorkerGroup) {
                workerGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS);
            }
        }
    }
}
