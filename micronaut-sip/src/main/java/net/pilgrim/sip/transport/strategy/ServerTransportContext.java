package net.pilgrim.sip.transport.strategy;

import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.metrics.SipMetrics;
import net.pilgrim.sip.parser.SipEncoder;
import net.pilgrim.sip.parser.SipParser;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.transport.SipResponseRouter;

/**
 * Shared runtime context provided to server transport strategies.
 */
public record ServerTransportContext(
        SipServerConfiguration configuration,
        SipDispatcher dispatcher,
        SipResponseRouter responseRouter,
        SipParser parser,
        SipEncoder encoder,
        SipMetrics metrics
) {}
