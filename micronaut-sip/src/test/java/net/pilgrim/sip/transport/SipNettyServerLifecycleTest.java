package net.pilgrim.sip.transport;

import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.metrics.SipMetrics;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.transport.strategy.AbstractStreamTransportStrategy;
import net.pilgrim.sip.transport.strategy.ClientTransportContext;
import net.pilgrim.sip.transport.strategy.ServerTransportContext;
import net.pilgrim.sip.transport.strategy.SipTransportRegistry;
import net.pilgrim.sip.transport.strategy.SipTransportStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SIP Netty Server Lifecycle and Client Transport Robustness Tests")
class SipNettyServerLifecycleTest {

    @Test
    @DisplayName("Startup failure cleanly terminates all previously started strategies (Issue 8)")
    void testStartupFailureStopsPreviouslyStartedStrategies() {
        AtomicBoolean strategy1Started = new AtomicBoolean(false);
        AtomicBoolean strategy1Stopped = new AtomicBoolean(false);

        SipTransportStrategy strategy1 = new SipTransportStrategy() {
            @Override
            public SipTransport getTransport() {
                return SipTransport.UDP;
            }

            @Override
            public boolean isEnabled(SipServerConfiguration configuration) {
                return true;
            }

            @Override
            public void startServer(ServerTransportContext context) {
                strategy1Started.set(true);
            }

            @Override
            public void stopServer() {
                strategy1Stopped.set(true);
            }

            @Override
            public boolean isRunning() {
                return strategy1Started.get() && !strategy1Stopped.get();
            }

            @Override
            public InetSocketAddress getBoundAddress() {
                return new InetSocketAddress("127.0.0.1", 5060);
            }

            @Override
            public int getPort(SipServerConfiguration configuration) {
                return 5060;
            }

            @Override
            public ChannelFuture sendServerMessage(SipMessage message, InetSocketAddress destination) {
                return null;
            }

            @Override
            public ChannelFuture sendClientStreamMessage(ClientTransportContext context, SipMessage message, InetSocketAddress destination) {
                return null;
            }

            @Override
            public ChannelInitializer<SocketChannel> createClientChannelInitializer(ClientTransportContext context, InetSocketAddress destination, ChannelInboundHandler clientHandler) {
                return null;
            }
        };

        SipTransportStrategy strategy2 = new SipTransportStrategy() {
            @Override
            public SipTransport getTransport() {
                return SipTransport.TCP;
            }

            @Override
            public boolean isEnabled(SipServerConfiguration configuration) {
                return true;
            }

            @Override
            public void startServer(ServerTransportContext context) {
                throw new RuntimeException("Simulated bind failure on TCP transport");
            }

            @Override
            public void stopServer() {
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public InetSocketAddress getBoundAddress() {
                return null;
            }

            @Override
            public int getPort(SipServerConfiguration configuration) {
                return 5061;
            }

            @Override
            public ChannelFuture sendServerMessage(SipMessage message, InetSocketAddress destination) {
                return null;
            }

            @Override
            public ChannelFuture sendClientStreamMessage(ClientTransportContext context, SipMessage message, InetSocketAddress destination) {
                return null;
            }

            @Override
            public ChannelInitializer<SocketChannel> createClientChannelInitializer(ClientTransportContext context, InetSocketAddress destination, ChannelInboundHandler clientHandler) {
                return null;
            }
        };

        SipTransportRegistry registry = new SipTransportRegistry(java.util.List.of(strategy1, strategy2));
        SipServerConfiguration config = new SipServerConfiguration();
        SipMetrics metrics = SipMetrics.NOOP;
        SipResponseRouter router = new SipResponseRouter();

        SipNettyServer server = new SipNettyServer(config, null, router, metrics, registry);

        RuntimeException ex = assertThrows(RuntimeException.class, server::start);
        assertTrue(ex.getMessage().contains("Could not start SIP server"));

        // Verify that strategy 1 was started and subsequently shut down during the failure recovery
        assertTrue(strategy1Started.get(), "Strategy 1 should have been started before strategy 2 failed");
        assertTrue(strategy1Stopped.get(), "Strategy 1 must be cleanly stopped when server fails to start");
        assertFalse(strategy1.isRunning(), "Strategy 1 must not be left running after startup failure");
    }

    @Test
    @DisplayName("Client stream send completes with failure on unreachable host (Issue 1)")
    void testClientStreamSendCompletesOnFailure() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            AbstractStreamTransportStrategy strategy = new AbstractStreamTransportStrategy() {
                @Override
                public SipTransport getTransport() {
                    return SipTransport.TCP;
                }

                @Override
                public boolean isEnabled(SipServerConfiguration configuration) {
                    return true;
                }

                @Override
                protected void configureServerChannel(SocketChannel ch, ServerTransportContext context) {
                }

                @Override
                protected String getConfiguredHost(SipServerConfiguration config) {
                    return "127.0.0.1";
                }

                @Override
                protected int getConfiguredPort(SipServerConfiguration config) {
                    return 5061;
                }

                @Override
                public ChannelInitializer<SocketChannel> createClientChannelInitializer(ClientTransportContext context, InetSocketAddress destination, ChannelInboundHandler clientHandler) {
                    return null;
                }
            };

            ClientTransportContext clientContext = new ClientTransportContext(
                    new SipServerConfiguration(),
                    group,
                    new net.pilgrim.sip.parser.SipParser(),
                    new net.pilgrim.sip.parser.SipEncoder(),
                    null
            );

            // Connect to an unused port on localhost that immediately refuses connection
            InetSocketAddress unreachable = new InetSocketAddress("127.0.0.1", 59999);
            SipRequest request = new SipRequest(SipMethod.OPTIONS, SipUri.parse("sip:test@127.0.0.1:59999"));

            ChannelFuture future = strategy.sendClientStreamMessage(clientContext, request, unreachable);
            assertNotNull(future);

            // Future must complete with failure rather than hanging or completing before connection error
            future.await(5000);
            assertTrue(future.isDone());
            assertFalse(future.isSuccess());
            assertNotNull(future.cause());
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    @DisplayName("ReactiveSipClient throws IllegalArgumentException on unsupported URI transport parameter (Issue 3)")
    void testUnsupportedUriTransportThrowsException() {
        SipServerConfiguration config = new SipServerConfiguration();
        SipTransportRegistry registry = SipTransportRegistry.withDefaults();
        SipResponseRouter router = new SipResponseRouter();
        ReactiveSipClient client = new ReactiveSipClient(null, router, config, null, registry);

        SipRequest request = new SipRequest(SipMethod.INVITE, SipUri.parse("sip:alice@example.com;transport=sctp"));
        InetSocketAddress destination = new InetSocketAddress("127.0.0.1", 5060);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> client.send(request, destination));
        assertTrue(ex.getMessage().contains("Unsupported SIP transport parameter in URI: sctp"));
    }
}
