package net.pilgrim.sip.filter;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.sip.annotation.SipFilter;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;

/**
 * Structured access logging filter that records incoming SIP requests and responses with latency.
 */
@Singleton
@SipFilter(order = -800)
public class SipLoggingFilter implements SipServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger("net.pilgrim.sip.access");

    private final SipServerConfiguration configuration;

    public SipLoggingFilter() {
        this(new SipServerConfiguration());
    }

    @Inject
    public SipLoggingFilter(@Nullable SipServerConfiguration configuration) {
        this.configuration = configuration != null ? configuration : new SipServerConfiguration();
    }

    @Override
    public int getOrder() {
        return -800;
    }

    @Override
    public Publisher<SipResponse> doFilter(SipRequest request, SipFilterChain chain) {
        if (!configuration.isAccessLogEnabled()) {
            return chain.proceed(request);
        }

        long startNanos = System.nanoTime();
        LOG.info("--> {} {} [Call-ID: {}] from {}",
                request.getMethod(),
                request.getUri(),
                request.getCallId(),
                request.getRemoteAddress());

        return Flux.from(chain.proceed(request))
                .doOnNext(response -> {
                    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                    LOG.info("<-- {} {} ({}ms) [Call-ID: {}]",
                            response.getStatusCode(),
                            response.getReasonPhrase(),
                            durationMs,
                            request.getCallId());
                })
                .doOnComplete(() -> {
                    if (request.getMethod() == SipMethod.ACK) {
                        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                        LOG.info("<-- ACK processed ({}ms) [Call-ID: {}]", durationMs, request.getCallId());
                    }
                });
    }
}
