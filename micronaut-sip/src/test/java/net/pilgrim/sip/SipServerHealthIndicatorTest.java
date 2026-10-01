package net.pilgrim.sip;

import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthResult;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.management.SipServerHealthIndicator;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.session.SipSessionManager;
import net.pilgrim.sip.transport.SipNettyServer;
import net.pilgrim.sip.transport.SipResponseRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SipServerHealthIndicatorTest {

    private SipServerConfiguration configuration;
    private SipNettyServer server;
    private SipSessionManager sessionManager;
    private SipServerHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        configuration = new SipServerConfiguration();
        configuration.setUdpPort(0); // ephemeral
        configuration.setTcpPort(0); // ephemeral
        sessionManager = new SipSessionManager(Duration.ofMinutes(10), 100);
        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, configuration);
        SipResponseRouter responseRouter = new SipResponseRouter();
        server = new SipNettyServer(configuration, dispatcher, responseRouter);
        indicator = new SipServerHealthIndicator(server, sessionManager);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testHealthIndicatorWhenServerIsStopped() {
        HealthResult result = Mono.from(indicator.getResult()).block();
        assertNotNull(result);
        assertEquals("sip", result.getName());
        assertEquals(HealthStatus.DOWN, result.getStatus());

        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertNotNull(details);
        assertEquals(false, details.get("running"));
        assertEquals(0, details.get("activeSessions"));
    }

    @Test
    void testHealthIndicatorWhenServerIsRunning() {
        server.start();
        sessionManager.getOrCreateSession("call-health-1", new InetSocketAddress("127.0.0.1", 5060));

        HealthResult result = Mono.from(indicator.getResult()).block();
        assertNotNull(result);
        assertEquals("sip", result.getName());
        assertEquals(HealthStatus.UP, result.getStatus());

        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        assertNotNull(details);
        assertEquals(true, details.get("running"));
        assertEquals(1, details.get("activeSessions"));
        assertTrue((int) details.get("udpPort") > 0);
        assertTrue((int) details.get("tcpPort") > 0);
    }
}
