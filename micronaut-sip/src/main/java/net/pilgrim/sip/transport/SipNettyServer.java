package net.pilgrim.sip.transport;

import io.micronaut.context.annotation.Context;
import io.micronaut.core.annotation.Nullable;
import io.netty.channel.ChannelFuture;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.metrics.SipMetrics;
import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.parser.SipEncoder;
import net.pilgrim.sip.parser.SipParser;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.transport.strategy.ServerTransportContext;
import net.pilgrim.sip.transport.strategy.SipTransportRegistry;
import net.pilgrim.sip.transport.strategy.SipTransportStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Reactive Netty-based SIP Server supporting UDP, TCP, and TLS (RFC 3261 SIPS / RFC 5630).
 * Delegates transport-specific lifecycle and messaging to {@link SipTransportRegistry}.
 */
@Singleton
@Context
public class SipNettyServer {

    private static final Logger LOG = LoggerFactory.getLogger(SipNettyServer.class);

    private final SipServerConfiguration configuration;
    private final SipDispatcher dispatcher;
    private final SipResponseRouter responseRouter;
    private final SipMetrics metrics;
    private final SipTransportRegistry transportRegistry;
    private final SipParser parser;
    private final SipEncoder encoder = new SipEncoder();

    private volatile boolean running = false;

    public SipNettyServer(SipServerConfiguration configuration,
                          SipDispatcher dispatcher,
                          SipResponseRouter responseRouter) {
        this(configuration, dispatcher, responseRouter, null, null);
    }

    public SipNettyServer(SipServerConfiguration configuration,
                          SipDispatcher dispatcher,
                          SipResponseRouter responseRouter,
                          @Nullable SipMetrics metrics) {
        this(configuration, dispatcher, responseRouter, metrics, null);
    }

    @Inject
    public SipNettyServer(SipServerConfiguration configuration,
                          SipDispatcher dispatcher,
                          SipResponseRouter responseRouter,
                          @Nullable SipMetrics metrics,
                          @Nullable SipTransportRegistry transportRegistry) {
        this.configuration = configuration;
        this.dispatcher = dispatcher;
        this.responseRouter = responseRouter;
        this.metrics = metrics != null ? metrics : SipMetrics.NOOP;
        this.transportRegistry = transportRegistry != null ? transportRegistry : SipTransportRegistry.withDefaults();
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
            ServerTransportContext context = new ServerTransportContext(
                    configuration, dispatcher, responseRouter, parser, encoder, metrics
            );
            for (SipTransportStrategy strategy : transportRegistry.getAll()) {
                if (strategy.isEnabled(configuration)) {
                    strategy.startServer(context);
                }
            }
            running = true;
        } catch (Exception e) {
            LOG.error("Failed to start SIP Server: {}", e.getMessage(), e);
            stop();
            throw new RuntimeException("Could not start SIP server", e);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (!running) {
            return;
        }
        LOG.info("Shutting down SIP Netty Server...");
        running = false;
        for (SipTransportStrategy strategy : transportRegistry.getAll()) {
            strategy.stopServer();
            metrics.setTransportActive(strategy.getTransport().name().toLowerCase(), false);
        }
        LOG.info("SIP Netty Server stopped.");
    }

    public ChannelFuture send(SipMessage message, InetSocketAddress destination) {
        SipTransport transport = (message != null && message.getTransport() != null)
                ? message.getTransport()
                : SipTransport.UDP;
        return transportRegistry.get(transport).sendServerMessage(message, destination);
    }

    public ChannelFuture sendUdp(SipMessage message, InetSocketAddress destination) {
        return transportRegistry.get(SipTransport.UDP).sendServerMessage(message, destination);
    }

    public ChannelFuture sendTcp(SipMessage message, InetSocketAddress destination) {
        return transportRegistry.get(SipTransport.TCP).sendServerMessage(message, destination);
    }

    public ChannelFuture sendTls(SipMessage message, InetSocketAddress destination) {
        return transportRegistry.get(SipTransport.TLS).sendServerMessage(message, destination);
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return getUdpPort();
    }

    public int getUdpPort() {
        return transportRegistry.get(SipTransport.UDP).getPort(configuration);
    }

    public int getTcpPort() {
        return transportRegistry.get(SipTransport.TCP).getPort(configuration);
    }

    public int getTlsPort() {
        return transportRegistry.get(SipTransport.TLS).getPort(configuration);
    }

    public InetSocketAddress getBoundAddress() {
        return getUdpBoundAddress();
    }

    public InetSocketAddress getUdpBoundAddress() {
        return transportRegistry.get(SipTransport.UDP).getBoundAddress();
    }

    public InetSocketAddress getTcpBoundAddress() {
        return transportRegistry.get(SipTransport.TCP).getBoundAddress();
    }

    public InetSocketAddress getTlsBoundAddress() {
        return transportRegistry.get(SipTransport.TLS).getBoundAddress();
    }

    public boolean isTlsActive() {
        return transportRegistry.get(SipTransport.TLS).isRunning();
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

    public SipTransportRegistry getTransportRegistry() {
        return transportRegistry;
    }

    public String getAdvertisedIp() {
        return configuration.resolveAdvertisedIp();
    }
}
