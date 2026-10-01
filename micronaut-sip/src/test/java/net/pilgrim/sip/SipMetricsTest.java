package net.pilgrim.sip;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.pilgrim.sip.annotation.OnInvite;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.metrics.MicrometerSipMetrics;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SipMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private SipSessionManager sessionManager;
    private MicrometerSipMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        sessionManager = new SipSessionManager(Duration.ofMinutes(10), 100);
        metrics = new MicrometerSipMetrics(meterRegistry, sessionManager);
    }

    @AfterEach
    void tearDown() {
        if (meterRegistry != null) {
            meterRegistry.close();
        }
    }

    @Test
    void testDirectMetricsRecording() {
        metrics.requestReceived("INVITE", "udp");
        metrics.requestReceived("INVITE", "udp");
        metrics.requestReceived("BYE", "tcp");

        Counter inviteCounter = meterRegistry.find("sip.server.requests")
                .tag("method", "INVITE")
                .tag("transport", "udp")
                .counter();
        assertNotNull(inviteCounter);
        assertEquals(2.0, inviteCounter.count());

        Counter byeCounter = meterRegistry.find("sip.server.requests")
                .tag("method", "BYE")
                .tag("transport", "tcp")
                .counter();
        assertNotNull(byeCounter);
        assertEquals(1.0, byeCounter.count());

        metrics.responseSent("INVITE", 200, "udp");
        Counter res200 = meterRegistry.find("sip.server.responses")
                .tag("method", "INVITE")
                .tag("status_code", "200")
                .tag("status_family", "2xx")
                .tag("transport", "udp")
                .counter();
        assertNotNull(res200);
        assertEquals(1.0, res200.count());

        metrics.requestDuration("INVITE", 200, Duration.ofMillis(35));
        Timer timer = meterRegistry.find("sip.server.request.duration")
                .tag("method", "INVITE")
                .tag("status_family", "2xx")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());

        metrics.requestRejected("missing_mandatory_headers");
        Counter rejCounter = meterRegistry.find("sip.server.rejected")
                .tag("reason", "missing_mandatory_headers")
                .counter();
        assertNotNull(rejCounter);
        assertEquals(1.0, rejCounter.count());
    }

    @Test
    void testGaugesForSessionsAndTransport() {
        Gauge sessionGauge = meterRegistry.find("sip.sessions.active").gauge();
        assertNotNull(sessionGauge);
        assertEquals(0.0, sessionGauge.value());

        sessionManager.getOrCreateSession("call-1", new InetSocketAddress("127.0.0.1", 5060));
        sessionManager.getOrCreateSession("call-2", new InetSocketAddress("127.0.0.1", 5060));
        assertEquals(2.0, sessionGauge.value());

        Gauge udpGauge = meterRegistry.find("sip.server.transport.active").tag("transport", "udp").gauge();
        Gauge tcpGauge = meterRegistry.find("sip.server.transport.active").tag("transport", "tcp").gauge();
        assertNotNull(udpGauge);
        assertNotNull(tcpGauge);
        assertEquals(0.0, udpGauge.value());
        assertEquals(0.0, tcpGauge.value());

        metrics.setTransportActive("udp", true);
        metrics.setTransportActive("tcp", true);
        assertEquals(1.0, udpGauge.value());
        assertEquals(1.0, tcpGauge.value());

        metrics.setTransportActive("udp", false);
        assertEquals(0.0, udpGauge.value());
    }

    @SipController
    static class TestInviteController {
        @OnInvite
        public SipResponse handleInvite(SipRequest req) {
            return SipResponse.ok(req);
        }
    }

    @Test
    void testDispatcherInstrumentsRequestsAndResponses() {
        SipServerConfiguration config = new SipServerConfiguration();
        config.setAuto100TryingEnabled(false);
        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config, metrics);
        dispatcher.registerController(new TestInviteController());

        SipRequest validInvite = SipRequest.builder(SipMethod.INVITE, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com;tag=1928301774")
                .to("sip:bob@biloxi.com")
                .callId("metrics-test-call-1")
                .cseq(1, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-metrics-1")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 5060))
                .build();
        validInvite.setTransport(SipTransport.UDP);

        AtomicReference<SipResponse> emitted = new AtomicReference<>();
        dispatcher.dispatch(validInvite, emitted::set);

        assertNotNull(emitted.get());
        assertEquals(200, emitted.get().getStatusCode());

        Counter reqCount = meterRegistry.find("sip.server.requests")
                .tag("method", "INVITE")
                .tag("transport", "udp")
                .counter();
        assertNotNull(reqCount);
        assertEquals(1.0, reqCount.count());

        Counter respCount = meterRegistry.find("sip.server.responses")
                .tag("method", "INVITE")
                .tag("status_code", "200")
                .tag("status_family", "2xx")
                .tag("transport", "udp")
                .counter();
        assertNotNull(respCount);
        assertEquals(1.0, respCount.count());
    }

    @Test
    void testDispatcherInstrumentsRejections() {
        SipServerConfiguration config = new SipServerConfiguration();
        SipDispatcher dispatcher = new SipDispatcher(null, sessionManager, config, metrics);

        // Request missing To header
        SipRequest malformed = new SipRequest(SipMethod.INVITE, "sip:bob@biloxi.com");
        malformed.setTransport(SipTransport.UDP);
        malformed.getHeaders().setFrom("sip:alice@atlanta.com");
        malformed.getHeaders().setCallId("malformed-call");
        malformed.getHeaders().setCSeq("1 INVITE");
        malformed.getHeaders().addVia("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-malformed");

        AtomicReference<SipResponse> emitted = new AtomicReference<>();
        dispatcher.dispatch(malformed, emitted::set);

        assertNotNull(emitted.get());
        assertEquals(400, emitted.get().getStatusCode());

        Counter rejCount = meterRegistry.find("sip.server.rejected")
                .tag("reason", "missing_mandatory_headers")
                .counter();
        assertNotNull(rejCount);
        assertEquals(1.0, rejCount.count());
    }
}
