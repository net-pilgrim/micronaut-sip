package net.pilgrim.sip.bdd.engine;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import net.pilgrim.sip.bdd.model.ScenarioContext;
import net.pilgrim.sip.bdd.model.VirtualUserAgent;
import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.parser.SipEncoder;
import net.pilgrim.sip.parser.SipParser;
import net.pilgrim.sip.transport.SipDatagramCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Factory and lifecycle manager for VirtualUserAgent instances over Netty.
 */
public class VirtualUserAgentManager {

    private static final Logger LOG = LoggerFactory.getLogger(VirtualUserAgentManager.class);

    private final SipParser parser = new SipParser();
    private final SipEncoder encoder = new SipEncoder();

    public VirtualUserAgent createEndpoint(String name, String host, int port, ScenarioContext context) {
        String effectiveHost = (host == null || host.isBlank()) ? "127.0.0.1" : host;
        EventLoopGroup group = new NioEventLoopGroup(1);
        AtomicReference<VirtualUserAgent> agentRef = new AtomicReference<>();

        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(DatagramChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast("sipCodec", new SipDatagramCodec(parser, encoder));
                            p.addLast("agentHandler", new SimpleChannelInboundHandler<SipMessage>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, SipMessage msg) {
                                    VirtualUserAgent agent = agentRef.get();
                                    if (agent != null) {
                                        agent.onMessageReceived(msg, msg.getRemoteAddress());
                                    }
                                }
                            });
                        }
                    });

            InetSocketAddress bindTarget = new InetSocketAddress(effectiveHost, port);
            ChannelFuture future = bootstrap.bind(bindTarget).sync();
            Channel channel = future.channel();
            InetSocketAddress boundAddress = (InetSocketAddress) channel.localAddress();

            String sipUri = String.format("sip:%s@%s:%d", name.toLowerCase(), boundAddress.getHostString(), boundAddress.getPort());

            VirtualUserAgent agent = new VirtualUserAgent(
                    name,
                    sipUri,
                    boundAddress,
                    SipTransport.UDP,
                    channel,
                    group,
                    context
            );

            agentRef.set(agent);
            context.registerActor(agent);
            LOG.info("Created Virtual User Agent '{}' listening on {}", name, boundAddress);
            return agent;

        } catch (Exception e) {
            group.shutdownGracefully();
            throw new RuntimeException("Failed to create VirtualUserAgent '" + name + "': " + e.getMessage(), e);
        }
    }
}
