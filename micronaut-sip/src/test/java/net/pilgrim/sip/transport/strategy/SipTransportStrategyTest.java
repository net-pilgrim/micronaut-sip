package net.pilgrim.sip.transport.strategy;

import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.model.SipUri;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SIP Transport Strategy Pattern Tests")
class SipTransportStrategyTest {

    private SipTransportRegistry registry;
    private SipServerConfiguration config;

    @BeforeEach
    void setUp() {
        registry = new SipTransportRegistry();
        config = new SipServerConfiguration();
        config.setUdpPort(5060);
        config.setTcpPort(5061);
        config.setTlsPort(5062);
    }

    @Test
    @DisplayName("Default registry contains strategies for UDP, TCP, and TLS")
    void testRegistryStrategiesAvailable() {
        assertTrue(registry.getStrategy(SipTransport.UDP).isPresent());
        assertTrue(registry.getStrategy(SipTransport.TCP).isPresent());
        assertTrue(registry.getStrategy(SipTransport.TLS).isPresent());

        assertInstanceOf(UdpTransportStrategy.class, registry.getStrategy(SipTransport.UDP).get());
        assertInstanceOf(TcpTransportStrategy.class, registry.getStrategy(SipTransport.TCP).get());
        assertInstanceOf(TlsTransportStrategy.class, registry.getStrategy(SipTransport.TLS).get());
    }

    @Test
    @DisplayName("SipTransport reliable and secure metadata invariants")
    void testTransportMetadata() {
        assertFalse(SipTransport.UDP.isReliable());
        assertFalse(SipTransport.UDP.isSecure());
        assertNull(SipTransport.UDP.getUriParameter());
        assertEquals("sip", SipTransport.UDP.getUriScheme());

        assertTrue(SipTransport.TCP.isReliable());
        assertFalse(SipTransport.TCP.isSecure());
        assertEquals("tcp", SipTransport.TCP.getUriParameter());
        assertEquals("sip", SipTransport.TCP.getUriScheme());

        assertTrue(SipTransport.TLS.isReliable());
        assertTrue(SipTransport.TLS.isSecure());
        assertEquals("tls", SipTransport.TLS.getUriParameter());
        assertEquals("sips", SipTransport.TLS.getUriScheme());

        assertEquals(SipTransport.UDP, SipTransport.fromVia("SIP/2.0/UDP"));
        assertEquals(SipTransport.TCP, SipTransport.fromVia("SIP/2.0/TCP"));
        assertEquals(SipTransport.TLS, SipTransport.fromVia("SIP/2.0/TLS"));
        assertThrows(IllegalArgumentException.class, () -> SipTransport.fromVia("SIP/2.0/SCTP"));
        assertThrows(IllegalArgumentException.class, () -> SipTransport.fromVia("SIP/2.0/UNKNOWN"));
    }

    @Test
    @DisplayName("Registry throws IllegalArgumentException on unsupported transport")
    void testUnsupportedTransportThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> registry.get(SipTransport.WS));
        assertThrows(IllegalArgumentException.class, () -> registry.get(SipTransport.WSS));
    }

    @Test
    @DisplayName("Registry accurately resolves server ports per transport")
    void testResolveServerPort() {
        SipRequest udpReq = new SipRequest(SipMethod.INVITE, SipUri.parse("sip:bob@example.com"));
        udpReq.setTransport(SipTransport.UDP);
        assertEquals(5060, registry.resolveServerPort(udpReq, config));

        SipRequest tcpReq = new SipRequest(SipMethod.INVITE, SipUri.parse("sip:bob@example.com"));
        tcpReq.setTransport(SipTransport.TCP);
        assertEquals(5061, registry.resolveServerPort(tcpReq, config));

        SipRequest tlsReq = new SipRequest(SipMethod.INVITE, SipUri.parse("sip:bob@example.com"));
        tlsReq.setTransport(SipTransport.TLS);
        assertEquals(5062, registry.resolveServerPort(tlsReq, config));
    }

    @Test
    @DisplayName("Registry builds compliant Contact URIs with appropriate schemes and parameters")
    void testFormatContactUri() {
        SipRequest udpReq = new SipRequest(SipMethod.INVITE, SipUri.parse("sip:bob@example.com"));
        udpReq.setTransport(SipTransport.UDP);
        assertEquals("<sip:192.168.1.50:5060>", registry.formatContactUri(udpReq, "192.168.1.50", 5060));

        SipRequest tcpReq = new SipRequest(SipMethod.INVITE, SipUri.parse("sip:bob@example.com"));
        tcpReq.setTransport(SipTransport.TCP);
        assertEquals("<sip:192.168.1.50:5061;transport=tcp>", registry.formatContactUri(tcpReq, "192.168.1.50", 5061));

        SipRequest tlsReq = new SipRequest(SipMethod.INVITE, SipUri.parse("sip:bob@example.com"));
        tlsReq.setTransport(SipTransport.TLS);
        assertEquals("<sips:192.168.1.50:5062;transport=tls>", registry.formatContactUri(tlsReq, "192.168.1.50", 5062));

        // Contact URIs with specified user part (e.g. annc)
        assertEquals("<sips:annc@192.168.1.50:5062;transport=tls>", registry.formatContactUri(tlsReq, "192.168.1.50", 5062, "annc"));
        assertEquals("<sip:annc@192.168.1.50:5060>", registry.formatContactUri(udpReq, "192.168.1.50", 5060, "annc"));
    }
}
