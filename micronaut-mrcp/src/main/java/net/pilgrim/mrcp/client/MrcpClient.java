package net.pilgrim.mrcp.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import net.pilgrim.mrcp.codec.MrcpNettyDecoder;
import net.pilgrim.mrcp.codec.MrcpNettyEncoder;
import net.pilgrim.mrcp.message.MrcpEvent;
import net.pilgrim.mrcp.message.MrcpMessage;
import net.pilgrim.mrcp.message.MrcpRequest;
import net.pilgrim.mrcp.message.MrcpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.Closeable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reactive Netty client for MRCPv2 protocol communication over TCP.
 */
public class MrcpClient implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(MrcpClient.class);

    private final String host;
    private final int port;
    private final AtomicLong nextRequestId = new AtomicLong(1);

    private final ConcurrentMap<Long, Sinks.One<MrcpResponse>> pendingResponses = new ConcurrentHashMap<>();
    private final Sinks.Many<MrcpEvent> eventSink = Sinks.many().replay().limit(256);

    private EventLoopGroup group;
    private Channel channel;

    public MrcpClient(String host, int port) {
        this.host = (host != null) ? host : "127.0.0.1";
        this.port = port;
    }

    public synchronized void connect() {
        if (channel != null && channel.isActive()) {
            return;
        }

        this.group = new NioEventLoopGroup(1);
        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
             .channel(NioSocketChannel.class)
             .option(ChannelOption.SO_KEEPALIVE, true)
             .option(ChannelOption.TCP_NODELAY, true)
             .handler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ChannelPipeline p = ch.pipeline();
                     p.addLast("decoder", new MrcpNettyDecoder());
                     p.addLast("encoder", new MrcpNettyEncoder());
                     p.addLast("handler", new MrcpClientHandler());
                 }
             });

            ChannelFuture future = b.connect(host, port).sync();
            this.channel = future.channel();
            LOG.info("Connected MRCP client to {}:{}", host, port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted connecting to MRCP server", e);
        }
    }

    public long nextRequestId() {
        return nextRequestId.getAndIncrement();
    }

    public Mono<MrcpResponse> sendRequest(MrcpRequest request) {
        if (channel == null || !channel.isActive()) {
            return Mono.error(new IllegalStateException("MRCP client is not connected"));
        }

        Sinks.One<MrcpResponse> responseSink = Sinks.one();
        pendingResponses.put(request.getRequestId(), responseSink);

        channel.writeAndFlush(request).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                pendingResponses.remove(request.getRequestId());
                responseSink.tryEmitError(future.cause());
            }
        });

        return responseSink.asMono();
    }

    public Flux<MrcpEvent> events() {
        return eventSink.asFlux();
    }

    public Flux<MrcpEvent> events(long requestId) {
        return eventSink.asFlux().filter(e -> e.getRequestId() == requestId);
    }

    public boolean isConnected() {
        return channel != null && channel.isActive();
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
        pendingResponses.clear();
    }

    private class MrcpClientHandler extends SimpleChannelInboundHandler<MrcpMessage> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, MrcpMessage msg) {
            if (msg instanceof MrcpResponse resp) {
                Sinks.One<MrcpResponse> sink = pendingResponses.remove(resp.getRequestId());
                if (sink != null) {
                    sink.tryEmitValue(resp);
                } else {
                    LOG.debug("No pending sink for MRCP response id={}", resp.getRequestId());
                }
            } else if (msg instanceof MrcpEvent event) {
                eventSink.tryEmitNext(event);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.warn("MRCP client channel exception: {}", cause.getMessage());
            ctx.close();
        }
    }
}
