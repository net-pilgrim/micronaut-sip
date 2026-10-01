package net.pilgrim.sip.management;

import io.micronaut.context.annotation.Requires;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import net.pilgrim.sip.session.SipSessionManager;
import net.pilgrim.sip.transport.SipNettyServer;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Micronaut HealthIndicator reporting the operational status of the SIP stack,
 * active transport ports (UDP/TCP), and live dialog/session count.
 */
@Singleton
@Requires(classes = HealthIndicator.class)
public class SipServerHealthIndicator implements HealthIndicator {

    private final SipNettyServer server;
    private final SipSessionManager sessionManager;

    public SipServerHealthIndicator(SipNettyServer server, SipSessionManager sessionManager) {
        this.server = server;
        this.sessionManager = sessionManager;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        boolean running = server.isRunning();
        HealthResult.Builder builder = running
                ? HealthResult.builder("sip", HealthStatus.UP)
                : HealthResult.builder("sip", HealthStatus.DOWN);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("running", running);
        details.put("udpPort", server.getUdpPort());
        details.put("tcpPort", server.getTcpPort());
        details.put("activeSessions", sessionManager.getActiveSessionCount());

        builder.details(details);
        return Mono.just(builder.build());
    }
}
