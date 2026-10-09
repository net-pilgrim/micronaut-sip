package net.pilgrim.netann;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Inject;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.transport.SipNettyServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = "test")
@Property(name = "netann.vxml.enabled", value = "false")
public class VxmlDisabledIntegrationTest {

    @Inject
    SipNettyServer server;

    @Inject
    ReactiveSipClient client;

    @Test
    void testWhenVxmlDisabledDialogInviteReturns488() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest invite = SipRequest.builder(SipMethod.INVITE,
                        "sip:dialog@127.0.0.1:" + server.getPort() + ";voicexml=classpath:vxml/sample_menu.vxml")
                .from("<sip:caller@127.0.0.1>;tag=caller-disabled-vxml")
                .to("<sip:dialog@127.0.0.1>")
                .callId("disabled-vxml-" + UUID.randomUUID())
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(488, response.getStatusCode(),
                "When netann.vxml.enabled=false, sip:dialog must fall through and return 488 Not Acceptable Here per RFC 4240 §2");
    }

    @Test
    void testWhenVxmlDisabledAnnouncementStillWorks() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        SipRequest invite = SipRequest.builder(SipMethod.INVITE,
                        "sip:annc@127.0.0.1:" + server.getPort() + ";play=builtin:tone:440,500")
                .from("<sip:caller@127.0.0.1>;tag=caller-annc-ok")
                .to("<sip:annc@127.0.0.1>")
                .callId("annc-ok-when-vxml-disabled-" + UUID.randomUUID())
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        assertEquals(200, response.getStatusCode(),
                "Announcement service must remain operational even when VXML is disabled");
    }
}
