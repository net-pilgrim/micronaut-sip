package net.pilgrim.sip.filter;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.sip.annotation.SipFilter;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.metrics.SipMetrics;
import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * High-performance, token-bucket IP rate limiter and anti-flood protection filter (RFC 3261 §21.5.4).
 * Drops or rejects abusive traffic with 503 Service Unavailable and Retry-After header.
 */
@Singleton
@SipFilter(order = Ordered.HIGHEST_PRECEDENCE)
public class SipRateLimitFilter implements SipServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(SipRateLimitFilter.class);

    private final SipServerConfiguration configuration;
    private final SipMetrics metrics;
    private final IpMatcher ipMatcher;
    private final IpRateLimiter rateLimiter;

    public SipRateLimitFilter() {
        this(new SipServerConfiguration(), null);
    }

    public SipRateLimitFilter(SipServerConfiguration configuration) {
        this(configuration, null);
    }

    @Inject
    public SipRateLimitFilter(@Nullable SipServerConfiguration configuration,
                              @Nullable SipMetrics metrics) {
        this.configuration = configuration != null ? configuration : new SipServerConfiguration();
        this.metrics = metrics != null ? metrics : SipMetrics.NOOP;
        this.ipMatcher = new IpMatcher(this.configuration.getRateLimitWhitelist());
        this.rateLimiter = new IpRateLimiter(
                this.configuration.getRateLimitRequestsPerSecond(),
                this.configuration.getRateLimitBurstCapacity(),
                this.configuration.getRateLimitMaxTrackedIps()
        );
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Publisher<SipResponse> doFilter(SipRequest request, SipFilterChain chain) {
        if (!configuration.isRateLimitEnabled()) {
            return chain.proceed(request);
        }

        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null) {
            // Cannot determine IP, allow through
            return chain.proceed(request);
        }

        InetAddress addr = remoteAddress.getAddress();
        String hostString = remoteAddress.getHostString();
        String ipKey = addr != null ? addr.getHostAddress() : hostString;

        // Check IP whitelist
        if (ipMatcher.matches(addr, hostString)) {
            LOG.trace("Bypassing rate limit for whitelisted IP: {}", ipKey);
            return chain.proceed(request);
        }

        // Check token bucket
        if (rateLimiter.tryConsume(ipKey)) {
            return chain.proceed(request);
        }

        // Rate limit exceeded!
        LOG.warn("Rate limit exceeded for IP: {} (Method: {}, Call-ID: {})", ipKey, request.getMethod(), request.getCallId());
        metrics.requestRejected("rate_limited");

        // RFC 3261 Section 17.2.1: ACKs must never be answered with an error response
        if (request.getMethod() == SipMethod.ACK) {
            LOG.debug("Silently dropping rate-limited ACK from {}", ipKey);
            return Flux.empty();
        }

        SipResponse response = SipResponse.serviceUnavailable(request, "Service Unavailable - Rate limit exceeded");
        response.getHeaders().set(SipHeaders.RETRY_AFTER, String.valueOf(configuration.getRateLimitRetryAfterSeconds()));
        return Mono.just(response);
    }

    public IpRateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public IpMatcher getIpMatcher() {
        return ipMatcher;
    }
}
