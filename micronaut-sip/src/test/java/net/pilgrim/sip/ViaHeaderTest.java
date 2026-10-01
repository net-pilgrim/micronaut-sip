package net.pilgrim.sip;

import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.model.ViaHeader;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class ViaHeaderTest {

    @Test
    void testParseStandardVia() {
        String raw = "SIP/2.0/UDP pc33.atlanta.com:5060;branch=z9hG4bK776asdhds";
        ViaHeader via = ViaHeader.parse(raw);

        assertEquals("SIP/2.0/UDP", via.getProtocol());
        assertEquals("pc33.atlanta.com", via.getHost());
        assertEquals(5060, via.getPort());
        assertEquals("z9hG4bK776asdhds", via.getBranch());
        assertFalse(via.hasRport());
        assertNull(via.getReceived());
    }

    @Test
    void testParseIpv6Via() {
        String raw = "SIP/2.0/UDP [2001:db8::1]:5060;branch=z9hG4bK123";
        ViaHeader via = ViaHeader.parse(raw);

        assertEquals("SIP/2.0/UDP", via.getProtocol());
        assertEquals("2001:db8::1", via.getHost());
        assertEquals(5060, via.getPort());
        assertEquals("z9hG4bK123", via.getBranch());
    }

    @Test
    void testParseHostWithoutPort() {
        String raw = "SIP/2.0/UDP biloxi.com;branch=z9hG4bK123";
        ViaHeader via = ViaHeader.parse(raw);

        assertEquals("biloxi.com", via.getHost());
        assertEquals(-1, via.getPort());
    }

    @Test
    void testRportFlagAndValue() {
        String rawWithFlag = "SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK123;rport";
        ViaHeader via1 = ViaHeader.parse(rawWithFlag);

        assertTrue(via1.hasRport());
        assertEquals(-1, via1.getRport());

        via1.setRport(45678);
        assertEquals(45678, via1.getRport());
        assertTrue(via1.toString().contains("rport=45678"));

        String rawWithValue = "SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK123;rport=54321;received=198.51.100.1";
        ViaHeader via2 = ViaHeader.parse(rawWithValue);

        assertTrue(via2.hasRport());
        assertEquals(54321, via2.getRport());
        assertEquals("198.51.100.1", via2.getReceived());
    }

    @Test
    void testResolveResponseAddress() {
        // 1. With rport and received
        ViaHeader via1 = ViaHeader.parse("SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1;received=203.0.113.195;rport=45678");
        InetSocketAddress addr1 = via1.resolveResponseAddress(new InetSocketAddress("127.0.0.1", 5060));
        assertEquals("203.0.113.195", addr1.getHostString());
        assertEquals(45678, addr1.getPort());

        // 2. Without rport, with received and sent-by port
        ViaHeader via2 = ViaHeader.parse("SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK2;received=203.0.113.195");
        InetSocketAddress addr2 = via2.resolveResponseAddress(new InetSocketAddress("127.0.0.1", 5060));
        assertEquals("203.0.113.195", addr2.getHostString());
        assertEquals(5060, addr2.getPort());

        // 3. Without received, sent-by host and port
        ViaHeader via3 = ViaHeader.parse("SIP/2.0/UDP 192.168.1.50:5080;branch=z9hG4bK3");
        InetSocketAddress addr3 = via3.resolveResponseAddress(null);
        assertEquals("192.168.1.50", addr3.getHostString());
        assertEquals(5080, addr3.getPort());
    }

    @Test
    void testProcessNatViaHostMismatch() {
        SipRequest request = SipRequest.builder(SipMethod.INVITE, "sip:bob@example.com")
                .from("<sip:alice@10.0.0.1>")
                .to("<sip:bob@example.com>")
                .via("SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1")
                .build();

        InetSocketAddress sender = new InetSocketAddress("203.0.113.5", 5060);
        request.processNatVia(sender);

        String topVia = request.getHeaders().getVia();
        assertNotNull(topVia);
        assertTrue(topVia.contains("received=203.0.113.5"));
        assertFalse(topVia.contains("rport"));
    }

    @Test
    void testProcessNatViaRportPopulated() {
        SipRequest request = SipRequest.builder(SipMethod.INVITE, "sip:bob@example.com")
                .from("<sip:alice@10.0.0.1>")
                .to("<sip:bob@example.com>")
                .via("SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1;rport")
                .build();

        InetSocketAddress sender = new InetSocketAddress("203.0.113.5", 43210);
        request.processNatVia(sender);

        String topVia = request.getHeaders().getVia();
        assertNotNull(topVia);
        assertTrue(topVia.contains("rport=43210"));
        assertTrue(topVia.contains("received=203.0.113.5"));

        // When creating response, destination should be resolved to 203.0.113.5:43210
        SipResponse response = request.createResponse(200);
        assertNotNull(response.getRemoteAddress());
        assertEquals("203.0.113.5", response.getRemoteAddress().getHostString());
        assertEquals(43210, response.getRemoteAddress().getPort());
    }

    @Test
    void testProcessNatViaSpoofingMitigation() {
        // Client tries to spoof received=8.8.8.8
        SipRequest request = SipRequest.builder(SipMethod.INVITE, "sip:bob@example.com")
                .from("<sip:alice@10.0.0.1>")
                .to("<sip:bob@example.com>")
                .via("SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1;received=8.8.8.8")
                .build();

        InetSocketAddress sender = new InetSocketAddress("203.0.113.5", 5060);
        request.processNatVia(sender);

        String topVia = request.getHeaders().getVia();
        assertNotNull(topVia);
        // Should overwrite spoofed IP with sender's real IP
        assertTrue(topVia.contains("received=203.0.113.5"));
        assertFalse(topVia.contains("received=8.8.8.8"));
    }
}
