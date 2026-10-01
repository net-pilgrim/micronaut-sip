package net.pilgrim.sip.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.sip.session.SipSessionManager;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer metrics binder and collector for the SIP stack.
 * Exposes counters, timers, and gauges for request traffic, response codes,
 * active sessions, transport status, and protocol rejections.
 */
@Singleton
@Requires(classes = MeterRegistry.class)
public class MicrometerSipMetrics implements SipMetrics, MeterBinder {

    private volatile MeterRegistry registry;
    private final SipSessionManager sessionManager;

    private final AtomicInteger udpActive = new AtomicInteger(0);
    private final AtomicInteger tcpActive = new AtomicInteger(0);

    private final ConcurrentMap<String, Counter> requestCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> responseCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> durationTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> rejectedCounters = new ConcurrentHashMap<>();

    public MicrometerSipMetrics() {
        this(null, null);
    }

    @Inject
    public MicrometerSipMetrics(@Nullable MeterRegistry registry, @Nullable SipSessionManager sessionManager) {
        this.registry = registry;
        this.sessionManager = sessionManager;
        if (registry != null) {
            bindTo(registry);
        }
    }

    @Override
    public synchronized void bindTo(MeterRegistry registry) {
        this.registry = registry;

        if (sessionManager != null) {
            Gauge.builder("sip.sessions.active", sessionManager, SipSessionManager::getActiveSessionCount)
                    .description("Current number of active SIP sessions")
                    .register(registry);
        }

        Gauge.builder("sip.server.transport.active", udpActive, AtomicInteger::get)
                .tag("transport", "udp")
                .description("Active state of the UDP SIP transport (1 if running, 0 if stopped)")
                .register(registry);

        Gauge.builder("sip.server.transport.active", tcpActive, AtomicInteger::get)
                .tag("transport", "tcp")
                .description("Active state of the TCP SIP transport (1 if running, 0 if stopped)")
                .register(registry);
    }

    @Override
    public void requestReceived(String method, String transport) {
        if (registry == null) return;
        String m = normalizeMethod(method);
        String t = normalizeTransport(transport);
        String key = m + ":" + t;

        requestCounters.computeIfAbsent(key, k ->
                Counter.builder("sip.server.requests")
                        .description("Total number of SIP requests received")
                        .tag("method", m)
                        .tag("transport", t)
                        .register(registry)
        ).increment();
    }

    @Override
    public void responseSent(String method, int statusCode, String transport) {
        if (registry == null) return;
        String m = normalizeMethod(method);
        String t = normalizeTransport(transport);
        String statusFamily = getStatusFamily(statusCode);
        String statusStr = String.valueOf(statusCode);
        String key = m + ":" + statusStr + ":" + t;

        responseCounters.computeIfAbsent(key, k ->
                Counter.builder("sip.server.responses")
                        .description("Total number of SIP responses sent")
                        .tag("method", m)
                        .tag("status_code", statusStr)
                        .tag("status_family", statusFamily)
                        .tag("transport", t)
                        .register(registry)
        ).increment();
    }

    @Override
    public void requestDuration(String method, int statusCode, Duration duration) {
        if (registry == null || duration == null) return;
        String m = normalizeMethod(method);
        String statusFamily = getStatusFamily(statusCode);
        String key = m + ":" + statusFamily;

        durationTimers.computeIfAbsent(key, k ->
                Timer.builder("sip.server.request.duration")
                        .description("Duration of SIP request processing")
                        .tag("method", m)
                        .tag("status_family", statusFamily)
                        .register(registry)
        ).record(duration);
    }

    @Override
    public void requestRejected(String reason) {
        if (registry == null) return;
        String r = (reason != null && !reason.isEmpty()) ? reason.toLowerCase(Locale.ROOT) : "unknown";

        rejectedCounters.computeIfAbsent(r, k ->
                Counter.builder("sip.server.rejected")
                        .description("Total number of rejected SIP requests")
                        .tag("reason", r)
                        .register(registry)
        ).increment();
    }

    @Override
    public void setTransportActive(String transport, boolean active) {
        if (transport == null) return;
        String t = transport.toLowerCase(Locale.ROOT);
        int val = active ? 1 : 0;
        if ("udp".equals(t)) {
            udpActive.set(val);
        } else if ("tcp".equals(t)) {
            tcpActive.set(val);
        }
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    public static String getStatusFamily(int statusCode) {
        if (statusCode >= 100 && statusCode < 200) return "1xx";
        if (statusCode >= 200 && statusCode < 300) return "2xx";
        if (statusCode >= 300 && statusCode < 400) return "3xx";
        if (statusCode >= 400 && statusCode < 500) return "4xx";
        if (statusCode >= 500 && statusCode < 600) return "5xx";
        if (statusCode >= 600 && statusCode < 700) return "6xx";
        return "unknown";
    }

    private static String normalizeMethod(String method) {
        return method != null ? method.toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private static String normalizeTransport(String transport) {
        return transport != null ? transport.toLowerCase(Locale.ROOT) : "unknown";
    }
}
