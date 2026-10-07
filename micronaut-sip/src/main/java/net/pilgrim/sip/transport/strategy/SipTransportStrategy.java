package net.pilgrim.sip.transport.strategy;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipTransport;

import java.net.InetSocketAddress;

/**
 * Strategy interface encapsulating protocol properties, server-side Netty pipeline management,
 * and client-side transmission for a specific {@link SipTransport}.
 */
public interface SipTransportStrategy {

    /**
     * @return the SIP transport type managed by this strategy
     */
    SipTransport getTransport();

    /**
     * @return true if this transport guarantees reliable delivery (TCP, TLS, WS, WSS)
     */
    default boolean isReliable() {
        return getTransport().isReliable();
    }

    /**
     * @return true if this transport uses cryptographic security (TLS, WSS)
     */
    default boolean isSecure() {
        return getTransport().isSecure();
    }

    /**
     * @return Via protocol token (e.g. "SIP/2.0/UDP", "SIP/2.0/TCP", "SIP/2.0/TLS")
     */
    default String getViaProtocol() {
        return getTransport().getViaProtocol();
    }

    /**
     * @return URI scheme for this transport ("sip" or "sips")
     */
    default String getUriScheme() {
        return getTransport().getUriScheme();
    }

    /**
     * @return transport parameter value for SIP URIs (e.g. "tcp", "tls", or null for UDP)
     */
    default String getUriParameter() {
        return getTransport().getUriParameter();
    }

    /**
     * Checks if this transport server is enabled in the configuration.
     */
    boolean isEnabled(SipServerConfiguration configuration);

    /**
     * Starts the server channel and event loops for this transport.
     */
    void startServer(ServerTransportContext context) throws InterruptedException;

    /**
     * Stops the server channel and cleanly shuts down associated event loop groups.
     */
    void stopServer();

    /**
     * @return true if the server channel is active and listening
     */
    boolean isRunning();

    /**
     * @return local socket address the server channel is bound to, or null if stopped
     */
    InetSocketAddress getBoundAddress();

    /**
     * Resolves the active listening port, or configured port if not yet bound.
     */
    int getPort(SipServerConfiguration configuration);

    /**
     * Sends an outbound SIP message using the server's channel/pipeline.
     */
    ChannelFuture sendServerMessage(SipMessage message, InetSocketAddress destination);

    /**
     * Sends an outbound SIP message over a client connection.
     */
    ChannelFuture sendClientStreamMessage(ClientTransportContext context, SipMessage message, InetSocketAddress destination);

    /**
     * Creates a Netty channel initializer for inbound client responses over stream connections.
     */
    ChannelInitializer<SocketChannel> createClientChannelInitializer(ClientTransportContext context,
                                                                     InetSocketAddress destination,
                                                                     ChannelInboundHandler clientHandler);

    /**
     * Formats a Via header line for outbound requests over this transport.
     */
    default String formatViaHeader(String host, int port, String branch, boolean rport) {
        return getViaProtocol() + " " + host + ":" + port + ";branch=" + branch + (rport ? ";rport" : "");
    }

    /**
     * Formats a Contact URI for this transport.
     */
    default String formatContactUri(String advertisedIp, int port, String user) {
        String scheme = getUriScheme();
        String userPart = (user != null && !user.isBlank()) ? (user + "@") : "";
        String param = getUriParameter();
        String transportParam = (param != null) ? (";transport=" + param) : "";
        return "<" + scheme + ":" + userPart + advertisedIp + ":" + port + transportParam + ">";
    }
}
