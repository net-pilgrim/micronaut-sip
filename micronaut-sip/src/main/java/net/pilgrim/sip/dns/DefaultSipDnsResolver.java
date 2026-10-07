package net.pilgrim.sip.dns;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.*;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.model.SipUri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Default RFC 3263 SIP Server Location Resolver.
 * Resolves SIP URIs via NAPTR -&gt; SRV -&gt; A/AAAA records with fallback.
 */
@Singleton
public class DefaultSipDnsResolver implements SipDnsResolver {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultSipDnsResolver.class);
    private static final Pattern IPV4_PATTERN = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern IPV6_PATTERN = Pattern.compile("^\\[?[0-9a-fA-F:]+\\]?$");

    public record SrvRecord(int priority, int weight, int port, String target) implements Comparable<SrvRecord> {
        @Override
        public int compareTo(SrvRecord o) {
            int cmp = Integer.compare(this.priority, o.priority);
            if (cmp != 0) return cmp;
            return Integer.compare(o.weight, this.weight);
        }
    }

    public record NaptrRecord(int order, int preference, String flags, String service, String regexp, String replacement)
            implements Comparable<NaptrRecord> {
        @Override
        public int compareTo(NaptrRecord o) {
            int cmp = Integer.compare(this.order, o.order);
            if (cmp != 0) return cmp;
            return Integer.compare(this.preference, o.preference);
        }
    }

    // Static test/local overrides for deterministic testing and private networks
    private final Map<String, List<NaptrRecord>> staticNaptr = new ConcurrentHashMap<>();
    private final Map<String, List<SrvRecord>> staticSrv = new ConcurrentHashMap<>();
    private final Map<String, List<InetAddress>> staticAddresses = new ConcurrentHashMap<>();

    private final NioEventLoopGroup eventLoopGroup;
    private final DnsNameResolver nettyResolver;

    public DefaultSipDnsResolver() {
        this.eventLoopGroup = new NioEventLoopGroup(1);
        this.nettyResolver = new DnsNameResolverBuilder(eventLoopGroup.next())
                .channelType(NioDatagramChannel.class)
                .optResourceEnabled(false)
                .build();
    }

    @PreDestroy
    public void shutdown() {
        if (nettyResolver != null) {
            nettyResolver.close();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
    }

    public void addStaticNaptr(String domain, int order, int preference, String flags, String service, String regexp, String replacement) {
        staticNaptr.computeIfAbsent(domain.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                .add(new NaptrRecord(order, preference, flags, service, regexp, replacement));
        Collections.sort(staticNaptr.get(domain.toLowerCase(Locale.ROOT)));
    }

    public void addStaticSrv(String serviceDomain, int priority, int weight, int port, String target) {
        staticSrv.computeIfAbsent(serviceDomain.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                .add(new SrvRecord(priority, weight, port, target));
        Collections.sort(staticSrv.get(serviceDomain.toLowerCase(Locale.ROOT)));
    }

    public void addStaticAddress(String host, InetAddress address) {
        staticAddresses.computeIfAbsent(host.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                .add(address);
    }

    public void clearStaticRecords() {
        staticNaptr.clear();
        staticSrv.clear();
        staticAddresses.clear();
    }

    @Override
    public Mono<List<SipResolvedDestination>> resolve(SipUri uri) {
        if (uri == null || uri.getHost() == null || uri.getHost().isBlank()) {
            return Mono.error(new IllegalArgumentException("SIP URI or host cannot be null or empty"));
        }

        String host = uri.getHost().trim();
        boolean isSips = "sips".equalsIgnoreCase(uri.getScheme());
        String transportParam = uri.getParameter("transport");
        SipTransport explicitTransport = parseTransport(transportParam);

        // 1. Host is numeric IP address (IPv4 / IPv6)
        if (isIpAddress(host)) {
            int port = uri.getPort() > 0 ? uri.getPort() : (isSips ? 5061 : 5060);
            SipTransport transport = explicitTransport != null ? explicitTransport : (isSips ? SipTransport.TLS : SipTransport.UDP);
            try {
                InetAddress addr = InetAddress.getByName(host);
                return Mono.just(List.of(new SipResolvedDestination(new InetSocketAddress(addr, port), transport)));
            } catch (UnknownHostException e) {
                return Mono.error(e);
            }
        }

        // 2. Explicit port specified in URI (RFC 3263 §4.1: no NAPTR/SRV lookup)
        if (uri.getPort() > 0) {
            int port = uri.getPort();
            SipTransport transport = explicitTransport != null ? explicitTransport : (isSips ? SipTransport.TLS : SipTransport.UDP);
            return resolveAddresses(host).map(addrs -> {
                List<SipResolvedDestination> destinations = new ArrayList<>();
                for (InetAddress addr : addrs) {
                    destinations.add(new SipResolvedDestination(new InetSocketAddress(addr, port), transport));
                }
                return destinations;
            });
        }

        // 3. Explicit transport parameter specified (no NAPTR lookup, query SRV directly)
        if (explicitTransport != null) {
            return resolveExplicitTransport(host, explicitTransport, isSips);
        }

        // 4. No explicit port, no explicit transport -> NAPTR lookup first, then SRV, then A/AAAA
        return resolveNaptr(host).flatMap(naptrList -> {
            if (!naptrList.isEmpty()) {
                // Process NAPTR records
                return resolveFromNaptrRecords(host, naptrList, isSips);
            } else {
                // No NAPTR records -> query default SRV records per RFC 3263 §4.1
                return resolveDefaultSrv(host, isSips);
            }
        });
    }

    private Mono<List<SipResolvedDestination>> resolveExplicitTransport(String host, SipTransport transport, boolean isSips) {
        String srvName = switch (transport) {
            case TLS -> "_sips._tcp." + host;
            case TCP -> "_sip._tcp." + host;
            case UDP -> "_sip._udp." + host;
            default -> "_sip._udp." + host;
        };

        return resolveSrv(srvName).flatMap(srvRecords -> {
            if (!srvRecords.isEmpty()) {
                return resolveFromSrvRecords(srvRecords, transport);
            } else {
                // Fallback to A/AAAA with default port
                int defaultPort = (transport == SipTransport.TLS || isSips) ? 5061 : 5060;
                return resolveAddresses(host).map(addrs -> {
                    List<SipResolvedDestination> list = new ArrayList<>();
                    for (InetAddress a : addrs) {
                        list.add(new SipResolvedDestination(new InetSocketAddress(a, defaultPort), transport));
                    }
                    return list;
                });
            }
        });
    }

    private Mono<List<SipResolvedDestination>> resolveDefaultSrv(String host, boolean isSips) {
        if (isSips) {
            return resolveSrv("_sips._tcp." + host).flatMap(srvList -> {
                if (!srvList.isEmpty()) {
                    return resolveFromSrvRecords(srvList, SipTransport.TLS);
                }
                return resolveAddresses(host).map(addrs ->
                        addrs.stream().map(a -> new SipResolvedDestination(new InetSocketAddress(a, 5061), SipTransport.TLS)).toList()
                );
            });
        }

        // For "sip:", query _sip._udp, then _sip._tcp
        return resolveSrv("_sip._udp." + host).flatMap(udpSrvList -> {
            if (!udpSrvList.isEmpty()) {
                return resolveFromSrvRecords(udpSrvList, SipTransport.UDP);
            }
            return resolveSrv("_sip._tcp." + host).flatMap(tcpSrvList -> {
                if (!tcpSrvList.isEmpty()) {
                    return resolveFromSrvRecords(tcpSrvList, SipTransport.TCP);
                }
                // Fallback to A/AAAA on port 5060 (UDP default)
                return resolveAddresses(host).map(addrs ->
                        addrs.stream().map(a -> new SipResolvedDestination(new InetSocketAddress(a, 5060), SipTransport.UDP)).toList()
                );
            });
        });
    }

    private Mono<List<SipResolvedDestination>> resolveFromNaptrRecords(String host, List<NaptrRecord> naptrList, boolean isSips) {
        List<Mono<List<SipResolvedDestination>>> monoList = new ArrayList<>();

        for (NaptrRecord rec : naptrList) {
            String service = rec.service().toUpperCase(Locale.ROOT);
            SipTransport transport = null;
            if (service.contains("SIPS+D2T")) {
                transport = SipTransport.TLS;
            } else if (!isSips && service.contains("SIP+D2T")) {
                transport = SipTransport.TCP;
            } else if (!isSips && service.contains("SIP+D2U")) {
                transport = SipTransport.UDP;
            }

            if (transport != null) {
                String replacement = rec.replacement();
                if (replacement != null && !replacement.isBlank() && !".".equals(replacement)) {
                    SipTransport finalTransport = transport;
                    monoList.add(resolveSrv(replacement).flatMap(srvList -> {
                        if (!srvList.isEmpty()) {
                            return resolveFromSrvRecords(srvList, finalTransport);
                        }
                        return Mono.just(List.of());
                    }));
                }
            }
        }

        if (monoList.isEmpty()) {
            return resolveDefaultSrv(host, isSips);
        }

        return Mono.zip(monoList, results -> {
            List<SipResolvedDestination> combined = new ArrayList<>();
            for (Object obj : results) {
                @SuppressWarnings("unchecked")
                List<SipResolvedDestination> sub = (List<SipResolvedDestination>) obj;
                combined.addAll(sub);
            }
            Collections.sort(combined);
            return combined;
        });
    }

    private Mono<List<SipResolvedDestination>> resolveFromSrvRecords(List<SrvRecord> srvRecords, SipTransport transport) {
        List<Mono<List<SipResolvedDestination>>> monoList = new ArrayList<>();
        for (SrvRecord srv : srvRecords) {
            monoList.add(resolveAddresses(srv.target()).map(addrs -> {
                List<SipResolvedDestination> dests = new ArrayList<>();
                for (InetAddress addr : addrs) {
                    dests.add(new SipResolvedDestination(
                            new InetSocketAddress(addr, srv.port()),
                            transport,
                            srv.priority(),
                            srv.weight(),
                            60
                    ));
                }
                return dests;
            }));
        }

        return Mono.zip(monoList, results -> {
            List<SipResolvedDestination> combined = new ArrayList<>();
            for (Object obj : results) {
                @SuppressWarnings("unchecked")
                List<SipResolvedDestination> sub = (List<SipResolvedDestination>) obj;
                combined.addAll(sub);
            }
            Collections.sort(combined);
            return combined;
        });
    }

    private Mono<List<NaptrRecord>> resolveNaptr(String domain) {
        String key = domain.toLowerCase(Locale.ROOT);
        if (staticNaptr.containsKey(key)) {
            return Mono.just(new ArrayList<>(staticNaptr.get(key)));
        }

        return Mono.create(sink -> {
            try {
                // Query NAPTR via Netty DnsNameResolver (NAPTR type is 35)
                DnsQuestion question = new DefaultDnsQuestion(domain, DnsRecordType.valueOf(35));
                nettyResolver.query(question).addListener(future -> {
                    if (future.isSuccess()) {
                        @SuppressWarnings("unchecked")
                        io.netty.channel.AddressedEnvelope<DnsResponse, InetSocketAddress> envelope =
                                (io.netty.channel.AddressedEnvelope<DnsResponse, InetSocketAddress>) future.getNow();
                        List<NaptrRecord> records = new ArrayList<>();
                        try {
                            DnsResponse response = envelope.content();
                            int count = response.count(DnsSection.ANSWER);
                            for (int i = 0; i < count; i++) {
                                DnsRecord record = response.recordAt(DnsSection.ANSWER, i);
                                if (record.type().intValue() == 35 && record instanceof DnsRawRecord raw) {
                                    // Parse NAPTR payload: order(2), pref(2), flags, services, regexp, replacement
                                    io.netty.buffer.ByteBuf buf = raw.content();
                                    if (buf.readableBytes() >= 4) {
                                        int order = buf.readUnsignedShort();
                                        int pref = buf.readUnsignedShort();
                                        String flags = readDnsString(buf);
                                        String service = readDnsString(buf);
                                        String regexp = readDnsString(buf);
                                        String replacement = readDnsName(buf);
                                        records.add(new NaptrRecord(order, pref, flags, service, regexp, replacement));
                                    }
                                }
                            }
                        } finally {
                            envelope.release();
                        }
                        Collections.sort(records);
                        sink.success(records);
                    } else {
                        // Empty on error/not found per RFC 3263 fallback
                        sink.success(Collections.emptyList());
                    }
                });
            } catch (Exception e) {
                sink.success(Collections.emptyList());
            }
        });
    }

    private Mono<List<SrvRecord>> resolveSrv(String serviceDomain) {
        String key = serviceDomain.toLowerCase(Locale.ROOT);
        if (staticSrv.containsKey(key)) {
            return Mono.just(new ArrayList<>(staticSrv.get(key)));
        }

        return Mono.create(sink -> {
            try {
                DnsQuestion question = new DefaultDnsQuestion(serviceDomain, DnsRecordType.SRV);
                nettyResolver.query(question).addListener(future -> {
                    if (future.isSuccess()) {
                        @SuppressWarnings("unchecked")
                        io.netty.channel.AddressedEnvelope<DnsResponse, InetSocketAddress> envelope =
                                (io.netty.channel.AddressedEnvelope<DnsResponse, InetSocketAddress>) future.getNow();
                        List<SrvRecord> records = new ArrayList<>();
                        try {
                            DnsResponse response = envelope.content();
                            int count = response.count(DnsSection.ANSWER);
                            for (int i = 0; i < count; i++) {
                                DnsRecord record = response.recordAt(DnsSection.ANSWER, i);
                                if (record.type() == DnsRecordType.SRV && record instanceof DnsRawRecord raw) {
                                    io.netty.buffer.ByteBuf buf = raw.content();
                                    if (buf.readableBytes() >= 6) {
                                        int priority = buf.readUnsignedShort();
                                        int weight = buf.readUnsignedShort();
                                        int port = buf.readUnsignedShort();
                                        String target = readDnsName(buf);
                                        records.add(new SrvRecord(priority, weight, port, target));
                                    }
                                }
                            }
                        } finally {
                            envelope.release();
                        }
                        Collections.sort(records);
                        sink.success(records);
                    } else {
                        sink.success(Collections.emptyList());
                    }
                });
            } catch (Exception e) {
                sink.success(Collections.emptyList());
            }
        });
    }

    private Mono<List<InetAddress>> resolveAddresses(String host) {
        String key = host.toLowerCase(Locale.ROOT);
        if (staticAddresses.containsKey(key)) {
            return Mono.just(new ArrayList<>(staticAddresses.get(key)));
        }

        return Mono.create(sink -> {
            try {
                InetAddress[] addrs = InetAddress.getAllByName(host);
                sink.success(Arrays.asList(addrs));
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    private static String readDnsString(io.netty.buffer.ByteBuf buf) {
        if (!buf.isReadable()) return "";
        int len = buf.readUnsignedByte();
        if (buf.readableBytes() < len) return "";
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String readDnsName(io.netty.buffer.ByteBuf buf) {
        StringBuilder sb = new StringBuilder();
        while (buf.isReadable()) {
            int len = buf.readUnsignedByte();
            if (len == 0) break;
            if (buf.readableBytes() < len) break;
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            if (!sb.isEmpty()) sb.append('.');
            sb.append(new String(bytes, java.nio.charset.StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }

    private boolean isIpAddress(String host) {
        if (host == null) return false;
        return IPV4_PATTERN.matcher(host).matches() || IPV6_PATTERN.matcher(host).matches();
    }

    private SipTransport parseTransport(String token) {
        if (token == null || token.isBlank()) return null;
        return switch (token.trim().toUpperCase(Locale.ROOT)) {
            case "UDP" -> SipTransport.UDP;
            case "TCP" -> SipTransport.TCP;
            case "TLS" -> SipTransport.TLS;
            case "WS" -> SipTransport.WS;
            case "WSS" -> SipTransport.WSS;
            default -> null;
        };
    }
}
