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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Top-level media manager coordinating high-concurrency RTP sessions.
 * Backed by a dedicated Netty event loop group for media traffic isolation.
 */
@Singleton
public class RtpMediaManager implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(RtpMediaManager.class);

    private final RtpConfiguration configuration;
    private final MediaPortManager portManager;
    private final EventLoopGroup workerGroup;
    private final boolean ownsWorkerGroup;
    private final RtpCodecRegistry codecRegistry;
    private final ConcurrentMap<String, RtpMediaSession> sessions = new ConcurrentHashMap<>();
    private final List<Consumer<RtpMediaSession>> sessionInitializers = new CopyOnWriteArrayList<>();
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

        int maxAttempts = 10;
        int attempts = 0;
        java.util.List<Integer> failedPorts = new java.util.ArrayList<>();

        while (attempts < maxAttempts) {
            int localPort = portManager.allocatePort();
            Sinks.Many<RtpInboundPacket> incomingSink = Sinks.many().multicast().onBackpressureBuffer(2048, false);

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(workerGroup)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
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
                for (Consumer<RtpMediaSession> initializer : sessionInitializers) {
                    try {
                        initializer.accept(session);
                    } catch (Throwable t) {
                        LOG.warn("Session initializer failed for Call-ID: {}", callId, t);
                    }
                }
                for (int p : failedPorts) {
                    portManager.releasePort(p);
                }
                sessions.put(callId, session);
                return session;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                portManager.releasePort(localPort);
                for (int p : failedPorts) {
                    portManager.releasePort(p);
                }
                throw new IOException("Interrupted while binding RTP port " + localPort + " for Call-ID: " + callId, e);
            } catch (Exception e) {
                failedPorts.add(localPort);
                attempts++;
                if (attempts >= maxAttempts) {
                    for (int p : failedPorts) {
                        portManager.releasePort(p);
                    }
                    throw new IOException("Failed to bind RTP port after " + maxAttempts + " attempts for Call-ID: " + callId + ": " + e.getMessage(), e);
                }
            }
        }
        throw new IOException("Failed to allocate RTP port for Call-ID: " + callId);
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

    public void addSessionInitializer(Consumer<RtpMediaSession> initializer) {
        if (initializer != null) {
            sessionInitializers.add(initializer);
        }
    }

    public void addAudioProcessor(AudioProcessor processor) {
        if (processor != null) {
            addSessionInitializer(session -> session.addAudioProcessor(processor));
        }
    }

    public void removeSessionInitializer(Consumer<RtpMediaSession> initializer) {
        if (initializer != null) {
            sessionInitializers.remove(initializer);
        }
    }

    public void clearSessionInitializers() {
        sessionInitializers.clear();
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
