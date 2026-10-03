package net.pilgrim.sip.rtp.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import net.pilgrim.sip.rtp.RtpPacket;
import net.pilgrim.sip.rtp.RtpPacketizer;
import net.pilgrim.sip.rtp.codec.RtpCodec;
import reactor.core.publisher.Mono;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance, asynchronous Netty-based UDP sender for RTP streams.
 * Can attach to an existing channel for symmetric RTP or create an isolated UDP socket.
 */
public final class RtpNettySender implements Closeable {

    private final Channel channel;
    private final EventLoopGroup workerGroup;
    private final boolean ownsWorkerGroup;
    private final boolean ownsChannel;
    private volatile InetSocketAddress destination;
    private final RtpPacketizer packetizer;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RtpNettySender(InetSocketAddress destination, RtpPacketizer packetizer) throws IOException {
        this(destination, packetizer, new NioEventLoopGroup(1, new DefaultThreadFactory("rtp-tx")), true);
    }

    public RtpNettySender(InetSocketAddress destination,
                          RtpPacketizer packetizer,
                          EventLoopGroup workerGroup,
                          boolean ownsWorkerGroup) throws IOException {
        this.destination = Objects.requireNonNull(destination, "destination");
        this.packetizer = Objects.requireNonNull(packetizer, "packetizer");
        this.workerGroup = Objects.requireNonNull(workerGroup, "workerGroup");
        this.ownsWorkerGroup = ownsWorkerGroup;
        this.ownsChannel = true;

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_SNDBUF, 1024 * 1024)
                .handler(new io.netty.channel.ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) {
                        ch.pipeline().addLast("codec", new RtpDatagramCodec(destination));
                    }
                });

        try {
            ChannelFuture future = bootstrap.bind(0).sync();
            this.channel = future.channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while binding RTP sender", e);
        } catch (Exception e) {
            if (ownsWorkerGroup) {
                workerGroup.shutdownGracefully(0, 50, TimeUnit.MILLISECONDS);
            }
            throw new IOException("Failed to bind RTP sender: " + e.getMessage(), e);
        }
    }

    /**
     * Creates an RtpNettySender sharing an existing Netty channel (RFC 4961 symmetric RTP).
     */
    public RtpNettySender(Channel channel, InetSocketAddress destination, RtpPacketizer packetizer) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.packetizer = Objects.requireNonNull(packetizer, "packetizer");
        this.workerGroup = channel.eventLoop();
        this.ownsWorkerGroup = false;
        this.ownsChannel = false;
    }

    public InetSocketAddress getDestination() {
        return destination;
    }

    public void setDestination(InetSocketAddress destination) {
        this.destination = Objects.requireNonNull(destination, "destination");
    }

    public RtpPacketizer getPacketizer() {
        return packetizer;
    }

    public Channel getChannel() {
        return channel;
    }

    public Mono<Void> sendPacket(RtpPacket packet) {
        return sendPacket(packet, this.destination);
    }

    public Mono<Void> sendPacket(RtpPacket packet, InetSocketAddress target) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(target, "target");
        return Mono.create(sink -> {
            channel.writeAndFlush(new RtpOutboundPacket(packet, target)).addListener(future -> {
                if (future.isSuccess()) {
                    sink.success();
                } else {
                    sink.error(future.cause());
                }
            });
        });
    }

    public Mono<Void> sendEncodedFrame(byte[] encodedPayload, int sampleCount, boolean marker) {
        RtpPacket packet = packetizer.packetize(encodedPayload, sampleCount, marker);
        return sendPacket(packet);
    }

    public Mono<Void> sendPcm16LeFrame(byte[] pcm16Le, RtpCodec codec, boolean marker) {
        Objects.requireNonNull(pcm16Le, "pcm16Le");
        Objects.requireNonNull(codec, "codec");
        byte[] encoded = codec.encodePcm16Le(pcm16Le);
        int sampleCount = pcm16Le.length / 2;
        return sendEncodedFrame(encoded, sampleCount, marker);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (ownsChannel) {
                channel.close();
            }
            if (ownsWorkerGroup) {
                workerGroup.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS);
            }
        }
    }
}
