package net.pilgrim.sip.dns;

import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.model.SipUri;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSipDnsResolverTest {

    private DefaultSipDnsResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DefaultSipDnsResolver();
    }

    @AfterEach
    void tearDown() {
        resolver.shutdown();
    }

    @Test
    void testNumericIpAddressResolution() {
        // 1. Direct IPv4 UDP
        SipUri udpUri = SipUri.parse("sip:alice@127.0.0.1:5060");
        List<SipResolvedDestination> udpDest = resolver.resolve(udpUri).block();
        assertNotNull(udpDest);
        assertEquals(1, udpDest.size());
        assertEquals(5060, udpDest.get(0).port());
        assertEquals(SipTransport.UDP, udpDest.get(0).transport());
        assertEquals("127.0.0.1", udpDest.get(0).address().getAddress().getHostAddress());

        // 2. Direct SIPS IPv4 default port 5061 and TLS
        SipUri sipsUri = SipUri.parse("sips:alice@127.0.0.1");
        List<SipResolvedDestination> sipsDest = resolver.resolve(sipsUri).block();
        assertNotNull(sipsDest);
        assertEquals(1, sipsDest.size());
        assertEquals(5061, sipsDest.get(0).port());
        assertEquals(SipTransport.TLS, sipsDest.get(0).transport());

        // 3. Direct IPv4 with explicit transport parameter
        SipUri tcpUri = SipUri.parse("sip:bob@127.0.0.1;transport=tcp");
        List<SipResolvedDestination> tcpDest = resolver.resolve(tcpUri).block();
        assertNotNull(tcpDest);
        assertEquals(1, tcpDest.size());
        assertEquals(5060, tcpDest.get(0).port());
        assertEquals(SipTransport.TCP, tcpDest.get(0).transport());
    }

    @Test
    void testExplicitPortInUriBypassesSrv() throws Exception {
        // RFC 3263 Section 4.1: If port is explicit, no SRV or NAPTR lookup is done
        InetAddress mockIp = InetAddress.getByName("192.0.2.1");
        resolver.addStaticAddress("sip.example.com", mockIp);

        SipUri uri = SipUri.parse("sip:alice@sip.example.com:5090");
        List<SipResolvedDestination> dest = resolver.resolve(uri).block();
        assertNotNull(dest);
        assertEquals(1, dest.size());
        assertEquals(5090, dest.get(0).port());
        assertEquals(SipTransport.UDP, dest.get(0).transport());
        assertEquals(mockIp, dest.get(0).address().getAddress());
    }

    @Test
    void testExplicitTransportQueriesSrvDirectly() throws Exception {
        InetAddress srvTargetIp = InetAddress.getByName("192.0.2.20");
        resolver.addStaticAddress("tls-proxy.example.com", srvTargetIp);
        resolver.addStaticSrv("_sips._tcp.example.com", 10, 60, 5061, "tls-proxy.example.com");

        SipUri uri = SipUri.parse("sip:alice@example.com;transport=tls");
        List<SipResolvedDestination> dest = resolver.resolve(uri).block();
        assertNotNull(dest);
        assertEquals(1, dest.size());
        assertEquals(5061, dest.get(0).port());
        assertEquals(SipTransport.TLS, dest.get(0).transport());
        assertEquals(srvTargetIp, dest.get(0).address().getAddress());
    }

    @Test
    void testNaptrResolutionPriorityAndTransportSelection() throws Exception {
        String domain = "telecom.example.com";
        InetAddress udpIp = InetAddress.getByName("192.0.2.100");
        InetAddress tlsIp = InetAddress.getByName("192.0.2.101");

        resolver.addStaticAddress("udp-node.telecom.example.com", udpIp);
        resolver.addStaticAddress("tls-node.telecom.example.com", tlsIp);

        // NAPTR records: lower order wins, lower preference breaks ties
        resolver.addStaticNaptr(domain, 10, 10, "s", "SIPS+D2T", "", "_sips._tcp.telecom.example.com");
        resolver.addStaticNaptr(domain, 10, 20, "s", "SIP+D2U", "", "_sip._udp.telecom.example.com");

        resolver.addStaticSrv("_sips._tcp.telecom.example.com", 10, 100, 5061, "tls-node.telecom.example.com");
        resolver.addStaticSrv("_sip._udp.telecom.example.com", 10, 100, 5060, "udp-node.telecom.example.com");

        // "sips:" scheme should select SIPS+D2T and ignore SIP+D2U
        SipUri sipsUri = SipUri.parse("sips:alice@" + domain);
        SipResolvedDestination primarySips = resolver.resolvePrimary(sipsUri).block();
        assertNotNull(primarySips);
        assertEquals(SipTransport.TLS, primarySips.transport());
        assertEquals(5061, primarySips.port());
        assertEquals(tlsIp, primarySips.address().getAddress());

        // "sip:" scheme selects SIPS+D2T first because order 10, pref 10 is higher priority than pref 20
        SipUri sipUri = SipUri.parse("sip:alice@" + domain);
        List<SipResolvedDestination> allDest = resolver.resolve(sipUri).block();
        assertNotNull(allDest);
        assertEquals(2, allDest.size());
        assertEquals(SipTransport.TLS, allDest.get(0).transport());
        assertEquals(SipTransport.UDP, allDest.get(1).transport());
    }

    @Test
    void testSrvPriorityAndWeightSorting() throws Exception {
        InetAddress primaryIp = InetAddress.getByName("192.0.2.1");
        InetAddress secondaryIp = InetAddress.getByName("192.0.2.2");
        InetAddress backupIp = InetAddress.getByName("192.0.2.3");

        resolver.addStaticAddress("srv1.example.com", primaryIp);
        resolver.addStaticAddress("srv2.example.com", secondaryIp);
        resolver.addStaticAddress("srv-backup.example.com", backupIp);

        // Priority 10 (higher weight 80 vs 20) -> Priority 20 (backup)
        resolver.addStaticSrv("_sip._udp.example.com", 10, 20, 5060, "srv2.example.com");
        resolver.addStaticSrv("_sip._udp.example.com", 10, 80, 5060, "srv1.example.com");
        resolver.addStaticSrv("_sip._udp.example.com", 20, 100, 5060, "srv-backup.example.com");

        SipUri uri = SipUri.parse("sip:bob@example.com");
        List<SipResolvedDestination> destinations = resolver.resolve(uri).block();
        assertNotNull(destinations);
        assertEquals(3, destinations.size());

        // First should be priority 10, weight 80 (srv1)
        assertEquals(primaryIp, destinations.get(0).address().getAddress());
        // Second should be priority 10, weight 20 (srv2)
        assertEquals(secondaryIp, destinations.get(1).address().getAddress());
        // Third should be priority 20 (srv-backup)
        assertEquals(backupIp, destinations.get(2).address().getAddress());
    }

    @Test
    void testFallbackToAddressRecordWhenNoSrv() throws Exception {
        InetAddress hostIp = InetAddress.getByName("192.0.2.55");
        resolver.addStaticAddress("plain.example.com", hostIp);

        // No NAPTR, no SRV configured for plain.example.com
        SipUri uri = SipUri.parse("sip:service@plain.example.com");
        List<SipResolvedDestination> destinations = resolver.resolve(uri).block();
        assertNotNull(destinations);
        assertFalse(destinations.isEmpty());

        SipResolvedDestination dest = destinations.get(0);
        assertEquals(5060, dest.port());
        assertEquals(SipTransport.UDP, dest.transport());
        assertEquals(hostIp, dest.address().getAddress());
    }
}
