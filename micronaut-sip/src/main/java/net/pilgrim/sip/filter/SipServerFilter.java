package net.pilgrim.sip.filter;

import io.micronaut.core.order.Ordered;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import org.reactivestreams.Publisher;

/**
 * Reactive filter contract for intercepting incoming SIP requests and outgoing SIP responses.
 * Filters can inspect or mutate requests, short-circuit responses (e.g. rate limiting or auth rejection),
 * wrap downstream responses (e.g. logging or header injection), or manage contextual state (e.g. MDC).
 */
public interface SipServerFilter extends Ordered {

    /**
     * Intercepts an incoming SIP request reactively.
     *
     * @param request the incoming SIP request
     * @param chain   the remaining filter chain
     * @return a publisher emitting one or more SIP responses
     */
    Publisher<SipResponse> doFilter(SipRequest request, SipFilterChain chain);

    /**
     * Determines whether this filter matches the given request.
     * Defaults to {@code true}.
     *
     * @param request the incoming SIP request
     * @return true if the filter should execute for this request, false to bypass
     */
    default boolean matches(SipRequest request) {
        return true;
    }

    /**
     * The order of execution. Lower values execute first (higher priority).
     *
     * @return the filter order, defaulting to 0
     */
    @Override
    default int getOrder() {
        return 0;
    }
}
