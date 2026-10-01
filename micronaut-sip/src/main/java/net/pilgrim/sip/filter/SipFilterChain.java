package net.pilgrim.sip.filter;

import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import org.reactivestreams.Publisher;

/**
 * Functional interface representing the remaining filter chain during SIP request execution.
 */
@FunctionalInterface
public interface SipFilterChain {

    /**
     * Proceeds with executing the next filter in the chain or the terminal controller handler.
     *
     * @param request the SIP request to process (possibly modified by previous filters)
     * @return a publisher emitting one or more SIP responses (or empty if none, e.g. for ACK)
     */
    Publisher<SipResponse> proceed(SipRequest request);
}
