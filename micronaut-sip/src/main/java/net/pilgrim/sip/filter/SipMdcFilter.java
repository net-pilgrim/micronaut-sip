package net.pilgrim.sip.filter;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.sip.annotation.SipFilter;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import org.reactivestreams.Publisher;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;

/**
 * Filter that propagates SIP tracking context (Call-ID, CSeq, Method, From, To, Remote-IP)
 * into SLF4J MDC for structured log correlation.
 */
@Singleton
@SipFilter(order = -900)
public class SipMdcFilter implements SipServerFilter {

    public static final String MDC_KEY_CALL_ID = "sip.callId";
    public static final String MDC_KEY_METHOD = "sip.method";
    public static final String MDC_KEY_CSEQ = "sip.cseq";
    public static final String MDC_KEY_FROM = "sip.from";
    public static final String MDC_KEY_TO = "sip.to";
    public static final String MDC_KEY_REMOTE = "sip.remote";

    private final SipServerConfiguration configuration;

    public SipMdcFilter() {
        this(new SipServerConfiguration());
    }

    @Inject
    public SipMdcFilter(@Nullable SipServerConfiguration configuration) {
        this.configuration = configuration != null ? configuration : new SipServerConfiguration();
    }

    @Override
    public int getOrder() {
        return -900;
    }

    @Override
    public Publisher<SipResponse> doFilter(SipRequest request, SipFilterChain chain) {
        if (!configuration.isMdcEnabled()) {
            return chain.proceed(request);
        }

        putMdc(request);
        return Flux.from(chain.proceed(request))
                .doOnEach(signal -> putMdc(request))
                .doFinally(signalType -> clearMdc());
    }

    private void putMdc(SipRequest request) {
        if (request.getCallId() != null) MDC.put(MDC_KEY_CALL_ID, request.getCallId());
        if (request.getMethod() != null) MDC.put(MDC_KEY_METHOD, request.getMethod().name());
        if (request.getCSeq() != null) MDC.put(MDC_KEY_CSEQ, request.getCSeq());
        if (request.getFrom() != null) MDC.put(MDC_KEY_FROM, request.getFrom());
        if (request.getTo() != null) MDC.put(MDC_KEY_TO, request.getTo());
        if (request.getRemoteAddress() != null) MDC.put(MDC_KEY_REMOTE, request.getRemoteAddress().toString());
    }

    private void clearMdc() {
        MDC.remove(MDC_KEY_CALL_ID);
        MDC.remove(MDC_KEY_METHOD);
        MDC.remove(MDC_KEY_CSEQ);
        MDC.remove(MDC_KEY_FROM);
        MDC.remove(MDC_KEY_TO);
        MDC.remove(MDC_KEY_REMOTE);
    }
}
