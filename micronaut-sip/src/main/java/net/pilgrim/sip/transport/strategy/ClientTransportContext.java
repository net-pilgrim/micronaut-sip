package net.pilgrim.sip.transport.strategy;

import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.parser.SipEncoder;
import net.pilgrim.sip.parser.SipParser;

/**
 * Shared runtime context provided to client transport strategies.
 */
public record ClientTransportContext(
        SipServerConfiguration configuration,
        EventLoopGroup clientWorkerGroup,
        SipParser parser,
        SipEncoder encoder,
        SslContext clientSslContext
) {}
