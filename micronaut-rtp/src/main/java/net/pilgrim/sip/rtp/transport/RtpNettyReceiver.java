package net.pilgrim.sip.rtp.transport;

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
import net.pilgrim.sip.rtp.RtpPacket;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance, asynchronous Netty-based UDP receiver for RTP streams.
 * Can latch remote client address for NAT traversal / symmetric RTP (RFC 4961).
 */
public final class RtpNettyReceiver implements Closeable {

    private final Channel channel;
    private final EventLoopGroup workerGroup;
    private final boolean ownsWorkerGroup;
    private final Sinks.Many<RtpInboundPacket> packetSink;
    private final BlockingQueue<RtpPacket> testQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile InetSocketAddress latchedRemoteAddress;

    public RtpNettyReceiver(int port) throws IOException {
        this(new InetSocketAddress(port));
    }

    public RtpNettyReceiver(InetSocketAddress bindAddress) throws IOException {
        this(bindAddress, new NioEventLoopGroup(1, new DefaultThreadFactory("rtp-rx")), true);
    }

    public RtpNettyReceiver(InetSocketAddress bindAddress, EventLoopGroup workerGroup, boolean ownsWorkerGroup) throws IOException {
        this.workerGroup = Objects.requireNonNull(workerGroup, "workerGroup");
        this.ownsWorkerGroup = ownsWorkerGroup;
        this.packetSink = Sinks.many().multicast().onBackpressureBuffer(2048, false);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_RCVBUF, 1024 * 1024)
                .handler(new io.netty.channel.ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) {
                        ch.pipeline().addLast("codec", new RtpDatagramCodec());
                        ch.pipeline().addLast("handler", new SimpleChannelInboundHandler<RtpInboundPacket>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, RtpInboundPacket msg) {
                                latchedRemoteAddress = msg.senderAddress();
                                packetSink.tryEmitNext(msg);
                                testQueue.offer(msg.packet());
                            }
                        });
                    }
                });

        try {
            ChannelFuture future = bootstrap.bind(bindAddress).sync();
            this.channel = future.channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while binding RTP receiver to " + bindAddress, e);
        } catch (Exception e) {
            if (ownsWorkerGroup) {
                workerGroup.shutdownGracefully(0, 50, TimeUnit.MILLISECONDS);
            }
            throw new IOException("Failed to bind RTP receiver to " + bindAddress + ": " + e.getMessage(), e);
        }
    }

    public int getLocalPort() {
        if (channel.localAddress() instanceof InetSocketAddress addr) {
            return addr.getPort();
        }
        return -1;
    }

    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) channel.localAddress();
    }

    public InetSocketAddress getLatchedRemoteAddress() {
        return latchedRemoteAddress;
    }

    public Channel getChannel() {
        return channel;
    }

    /**
     * Emits received inbound RTP packets reactively with sender metadata.
     */
    public Flux<RtpInboundPacket> incomingPackets() {
        return packetSink.asFlux();
    }

    /**
     * Emits received parsed RTP packets reactively.
     */
    public Flux<RtpPacket> incomingRtpPackets() {
        return packetSink.asFlux().map(RtpInboundPacket::packet);
    }

    /**
     * Convenience method for synchronous polling (e.g. tests).
     */
    public RtpPacket receiveNext(Duration timeout) throws InterruptedException {
        return testQueue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            packetSink.tryEmitComplete();
            channel.close();
            if (ownsWorkerGroup) {
                workerGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS);
            }
        }
    }
}
