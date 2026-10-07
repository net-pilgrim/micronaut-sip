# Micronaut Reactive SIP (RFC 3261) Multi-Module Project

A reactive [Micronaut](https://micronaut.io) multi-module Gradle project communicating over the **Session Initiation Protocol (SIP)** (RFC 3261) supporting **UDP**, **TCP**, and **TLS** transports powered by **Netty** and **Project Reactor**.

[![CI](https://github.com/net-pilgrim/micronaut-sip/actions/workflows/ci.yml/badge.svg)](https://github.com/net-pilgrim/micronaut-sip/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)

---

## Micronaut Performance Showcase

The Micronaut SIP application combines a reactive Netty transport with a GraalVM Native Image option. SIPp v3.7 benchmarks under identical conditions show the native executable's reduced startup time and memory footprint while maintaining comparable call throughput:

| Benchmark | JVM (Zulu JDK 25) | GraalVM Native Image | Native advantage |
| :--- | ---: | ---: | :--- |
| Startup time | 670 ms | 16–22 ms | ~30–40× faster |
| Initial idle memory (RSS) | ~146 MB | ~44 MB | ~70% lower |
| Average memory under 5-minute load | 269 MB | 92 MB | ~66% lower |
| Average CPU under 5-minute load | 13.0% | 10.5% | ~20% lower |
| Sustained call throughput | 49.95 calls/s | 49.92 calls/s | 15,000 calls; 100% success |

Measured mean call latency was approximately 52 ms for both builds; about 50 ms is the sample application's simulated pickup delay, leaving less than 2 ms of SIP stack processing overhead. See [Performance & Benchmarking](doc/PERFORMANCE.md) for full results, test details, and commands.

---

## Key Features

1. **RFC 3261 Compliant Multi-Transport SIP Stack**:
   - Asynchronous UDP transport using Netty `NioDatagramChannel`.
   - Streaming TCP transport using Netty `NioServerSocketChannel` / `NioSocketChannel` with stream frame decoding (`Content-Length` boundary framing per RFC 3261 §18.1.1) and keep-alive ping support (RFC 5626).
   - Secure TLS signaling (`SIP over TLS` / `SIPS` per RFC 3261 §19.1/§26 / RFC 5630) on port 5061 via Netty `SslHandler` supporting PKCS12/JKS keystores and mutual TLS client authentication (`clientAuth=REQUIRE`).
   - RFC 3263 DNS Server Location resolving SIP URIs via NAPTR (`SIPS+D2T`, `SIP+D2T`, `SIP+D2U`) -> SRV (`_sips._tcp`, `_sip._tcp`, `_sip._udp`) -> A/AAAA records with priority and weight balancing.
   - High-throughput parser and encoder handling request lines, status lines, multi-value headers, line folding, compact header aliases (`v`, `f`, `t`, `i`, `m`, `c`, `l`), and body payloads (e.g. SDP).
   - Compliant response generation (RFC 3261 §8.2.6) with automatic propagation of `Via`, `From`, `To` (with tag generation), `Call-ID`, and `CSeq`.
   - Request validation enforcing RFC 3261 §8.1/§8.2: missing mandatory headers yield `400 Bad Request`, unsupported methods yield `405 Method Not Allowed` with dynamic `Allow` header, and unsupported `Require` extensions yield `420 Bad Extension` with `Unsupported` header.
   - Reliable provisional responses (`100rel` / `RSeq`) and provisional response acknowledgement (`PRACK` / `RAck`) support with retransmission and transaction matching (RFC 3262).

2. **NAT Traversal & Symmetric Response Routing (RFC 3581 & RFC 3261 §18.2.1)**:
   - Automatic `Via` header processing and IP spoofing mitigation: inserts `received` parameter when source IP differs from `sent-by` host.
   - RFC 3581 `rport` support: populates client source port and routes responses to symmetric NAT address/port.

3. **Declarative SIP Controller & Filter Annotations (Zero Reflection / GraalVM Native AOT)**:
   - `@SipController`: Marks a class as a SIP controller component processed at compile time via `ExecutableMethodProcessor`.
   - `@SipFilter`: Declares a reactive server filter bean with optional URI patterns, method filtering, and execution order.
   - `@OnInvite`: Handles SIP `INVITE` requests.
   - `@OnBye`: Handles SIP `BYE` requests.
   - `@OnAck`: Handles SIP `ACK` requests.
   - `@OnCancel`: Handles SIP `CANCEL` requests.
   - `@OnRegister`: Handles SIP `REGISTER` requests.
   - `@OnOptions`: Handles SIP `OPTIONS` requests.
   - `@OnMessage`: Handles SIP `MESSAGE` instant messaging (RFC 3428).
   - `@OnInfo`: Handles SIP `INFO` requests for mid-dialog signaling and DTMF relay (RFC 2976 / RFC 6086).
   - `@OnPrack`: Handles SIP `PRACK` provisional response acknowledgements (RFC 3262).
   - `@OnSubscribe`: Handles SIP `SUBSCRIBE` event subscriptions (RFC 6665).
   - `@OnNotify`: Handles SIP `NOTIFY` event notifications (RFC 6665).
   - `@OnRefer`: Handles SIP `REFER` call transfer / event requests (RFC 3515).
   - `@OnUpdate`: Handles SIP `UPDATE` session modifications (RFC 3311).
   - `@OnPublish`: Handles SIP `PUBLISH` event state publications (RFC 3903).
   - `@OnSipMethod`: Generic handler for arbitrary SIP methods.
   - `@SipCallId`: Injects `Call-ID` directly into method parameters.
   - `@SipFrom`: Injects `From` header string or parsed `SipUri`.
   - `@SipTo`: Injects `To` header string or parsed `SipUri`.
   - `@SipParam`: Injects request-URI parameters with default values and type conversion (`String`, `int`, `long`, `boolean`).
   - `@SipHeader`: Injects SIP headers directly into method parameters with primitive type conversion.
   - `@SipBody`: Injects the message body (e.g., SDP or text) as `String` or `byte[]`.
   - `@SipDtmf`: Injects parsed DTMF tones (`DtmfSignal`, `char`, `String`) from `application/dtmf-relay` or `application/dtmf` bodies.

4. **End-to-End Reactive Programming**:
   - Controller methods can return:
     - `Mono<SipResponse>` for asynchronous single responses.
     - `Flux<SipResponse>` / `Publisher<SipResponse>` for streaming multiple responses (e.g., emitting `180 Ringing` followed by `200 OK`).
     - `CompletableFuture<SipResponse>` / `CompletionStage<SipResponse>`.
     - `SipResponse` for immediate synchronous responses.
     - `void` for requests requiring no response (such as `ACK`).

5. **Reactive SIP Client (`ReactiveSipClient`)**:
   - **UDP, TCP & TLS Support (RFC 3261 / RFC 5630)**:
     - `send(request, destination)`: final response (`Mono<SipResponse>`), with transport auto-resolved from SIP metadata (`Request-URI transport` / `sips:` scheme / `Via` / request transport).
     - `sendWithProvisional(request, destination)`: provisional + final responses (`Flux<SipResponse>`), with the same transport auto-resolution.
     - `send(request, destination, SipTransport)`: explicit override when transport must be forced.
   - **RFC 3263 DNS Location Overloads**:
     - `send(request)`: resolves target host and transport automatically via NAPTR -> SRV -> A/AAAA lookups.
     - `sendWithProvisional(request)`: reactive stream with DNS location resolution.
   - `sendAck(...)`: Sends an RFC 3261-compliant ACK over UDP, TCP, or TLS (`sendAckTls`), with optional SDP payload support for late-offer/answer call flows.
   - `sendPrack(...)`: Sends an RFC 3262-compliant PRACK acknowledging reliable provisional responses.
   - `sendBye(...)`: Terminates active calls over UDP, TCP, or TLS.
   - `sendOneWay(...)`: Fires one-way SIP requests without waiting for response.

6. **Dialog & Session Management (`SipSessionManager`)**:
   - Tracks call state transitions (`INITIAL` -> `EARLY` -> `CONFIRMED` -> `TERMINATED`).
   - Configurable session TTL (`sip.server.session-ttl-ms`, default 30m) and max session cap (`sip.server.max-sessions`, default 10k) with automatic LRU and expired session eviction.
   - Injects active `SipSession` into controller handler parameters.

7. **RFC 3261 §9.2 CANCEL Transaction Matching & Auto `487 Request Terminated`**:
   - Matches pending in-flight INVITE server transactions by topmost `Via` branch parameter and `Call-ID`.
   - Automatically responds to `CANCEL` with `200 OK`.
   - Automatically aborts the pending `INVITE` reactive pipeline and emits `487 Request Terminated` to the caller.
   - Automatically updates active dialog session state to `TERMINATED`.
   - Returns `481 Call/Transaction Does Not Exist` if no matching transaction is pending or if a final response was already sent.
   - Seamlessly invokes `@OnCancel` controller methods for application-level teardown.

8. **Type-Safe Exception Handling (`@SipError`)**:
   - Declarative exception mapping with nearest-ancestor inheritance hierarchy resolution.
   - Maps uncaught application exceptions to custom SIP responses (e.g., `404 Not Found`, `503 Service Unavailable`).

9. **Observability & Micrometer Metrics (`MicrometerSipMetrics`)**:
   - Out-of-the-box Prometheus metrics export via Micronaut Micrometer:
     - `sip.server.requests` (Counter: tagged by `method`, `transport`)
     - `sip.server.responses` (Counter: tagged by `method`, `status_code`, `status_family`, `transport`)
     - `sip.server.request.duration` (Timer: latency distribution tagged by `method`, `status_family`)
     - `sip.server.rejected` (Counter: tagged by `reason`)
     - `sip.sessions.active` (Gauge: real-time active dialog count)
     - `sip.server.transport.active` (Gauge: UDP and TCP channel state)

10. **Operations & Health Endpoint (`SipServerHealthIndicator`)**:
    - Integrates with Micronaut Management `/health` endpoint reporting `UP`/`DOWN` status, bound UDP and TCP ports, and active sessions count.

11. **DoS Flood Mitigation & `513 Message Too Large` (RFC 3261 §21.5.2)**:
    - Enforces configurable limits on maximum message size (default 64 KB), header count (default 100), and header length (default 8 KB).
    - Automatically rejects oversized requests with `513 Message Too Large`.

12. **Reactive Server Filter Chain (`@SipFilter` / `SipServerFilter`)**:
    - Non-blocking reactive interceptor pipeline for cross-cutting concerns (authentication, rate limiting, request/response header mutation, logging).
    - Declarative filtering via `@SipFilter(methods = ..., patterns = ..., order = ...)` and programmatic `Ordered.getOrder()`.
    - Includes built-in `SipMdcFilter` (propagates Call-ID, CSeq, method, and endpoints into SLF4J MDC) and `SipLoggingFilter` (structured request/response access logging with latency tracking).

13. **Token-Bucket IP Rate Limiting & Overload Protection (RFC 3261 §21.5.4)**:
    - High-throughput, lock-free per-IP token-bucket rate limiter with burst allowance and continuous replenishment.
    - Rejects abusive traffic with `503 Service Unavailable` and RFC 3261 `Retry-After: <seconds>` header.
    - Strict RFC 3261 §17.2.1 compliance: rate-limited `ACK` messages are dropped silently without generating error responses.
    - CIDR subnet and exact IP whitelisting (e.g. `10.0.0.0/8`, `192.168.0.0/16`, `127.0.0.1`, `::1`).
    - Bounded tracking cache with automatic idle entry eviction to prevent memory exhaustion from spoofed DoS sweeps.

14. **Basic SDP Handling Module (`micronaut-sdp`)**:
    - Lightweight SDP parser/model (`SdpParser`, `SdpMessage`) for session-level fields, media lines, and attributes.
    - Offer/answer helper (`SdpNegotiator`) supporting default audio offer generation and direction-aware SDP answer generation.

15. **High-Performance RTP Streaming Module (`micronaut-rtp`)**:
    - RTP packet model/parser (`RtpPacket`) with zero-copy Netty `ByteBuf` and `byte[]` serialization, and stream packetizer (`RtpPacketizer`).
    - Asynchronous Netty UDP transport (`RtpNettyReceiver`, `RtpNettySender`, `RtpDatagramCodec`) powered by dedicated media event loops.
    - High-concurrency media session coordinator (`RtpMediaManager`, `RtpMediaSession`) with dynamic port pair allocation (`MediaPortManager`, RFC 3550 §11) and symmetric RTP latching (RFC 4961).
    - Audio frame interceptors and DSP hooks (`AudioFrameProcessor`, `GoertzelDetector`, `SimpleVad`, `AudioFrame`).
    - Pluggable codec SPI (`RtpCodec`, `RtpCodecRegistry`) with G.711 support (`PCMU`/payload type `0`, `PCMA`/payload type `8`).
    - Standard blocking primitives (`RtpStreamSender`, `RtpStreamReceiver`) retained for lightweight/offline tooling.

16. **NetAnn Basic Media Announcement Service Module (`micronaut-netann`)**:
    - RFC 4240 compliant standalone Media Server Announcement Service (`sip:annc@...`).
    - Parses Request-URI parameters: `play=<uri>`, `repeat=<count|forever>`, `delay=<ms>`, `duration=<ms>`, `locale`, and `content-type`.
    - Resolves audio content from `classpath:`, `file:`, `/provisioned/`, `http(s):`, and synthetic frequencies (`tone:<freq>`), streaming 20ms G.711 PCMU/PCMA frames over RTP.
    - Strict RFC 4240 error handling: `400 Bad Request` on missing `play=`, `404 Not Found` on non-existent audio, and `488 Not Acceptable Here` on unhandled service indicators.
    - Automatic dialog completion: emits in-dialog `BYE` upon playback completion, or cleanly tears down background streaming upon caller `BYE`/`CANCEL`.
    - Dialog Service endpoints (`sip:dialog@...` and `sip:vxml@...`) gated behind `netann.vxml.enabled`, powered by `:micronaut-vxml`.

17. **VoiceXML 2.1 Dialog Engine Module (`micronaut-vxml`)**:
    - Standalone, protocol-neutral library module implementing the W3C VoiceXML 2.1 Form Interpretation Algorithm (FIA) runtime engine.
    - XXE-hardened XML parser and strongly-typed AST covering `<vxml>`, `<form>`, `<menu>`, `<field>`, `<block>`, `<prompt>`, `<choice>`, `<goto>`, `<if>`, `<elseif>`, `<assign>`, `<var>`, `<filled>`, `<noinput>`, `<nomatch>`, `<exit>`, `<disconnect>`, `<clear>`, and `<reprompt>`.
    - ECMAScript loose expression evaluator with operator coercion and scoping (dialog scope, document scope).
    - DTMF and speech grammar matcher supporting exact digits, wildcard matching, and choice shortcuts.
    - Abstract speech integration layer (`TtsClient`, `AsrClient`) with fallback synthetic tone generation (`ToneGenerator`).
    - Pluggable audio loader interface (`VxmlAudioLoader`) and output sink interface (`VxmlOutputSink`) enabling seamless integration with RTP media pipelines.

---

## RFC Compliance

See the [RFC compliance matrix](doc/COMPLIANCE.md) for implemented standards and their verification.

---

## Examples & Call Flows

- See [doc/EXAMPLES.md](doc/EXAMPLES.md) for SIP controller and reactive filter examples.
- See [doc/MICRONAUT-NETANN.md](doc/MICRONAUT-NETANN.md) for RFC 4240 NetAnn announcement service architecture, parameter specs, and detailed call flows with sequence diagrams.

---

## Kubernetes Deployment

See [k8s/README.md](k8s/README.md) for production Kubernetes manifests (`k8s/`) and deployment guidelines.

---

## Configuration

In [`sip-app/src/main/resources/application.properties`](sip-app/src/main/resources/application.properties):

```properties
micronaut.application.name=sip-app

# UDP Configuration
sip.server.udp-enabled=true
sip.server.udp.host=0.0.0.0
sip.server.udp.port=5060

# TCP Configuration
sip.server.tcp-enabled=true
sip.server.tcp.host=0.0.0.0
sip.server.tcp.port=5060

sip.server.enabled=true
sip.server.server-name=Micronaut-SIP/1.0
sip.server.request-timeout-ms=5000
sip.server.auto-cancel-enabled=true
sip.server.trying-delay-ms=200

# Token-Bucket IP Rate Limiting & Overload Protection (RFC 3261 §21.5.4)
sip.server.rate-limit.enabled=true
sip.server.rate-limit.requests-per-second=100
sip.server.rate-limit.burst-capacity=200
sip.server.rate-limit.retry-after-seconds=5
sip.server.rate-limit.whitelist=127.0.0.1,::1,10.0.0.0/8
sip.server.rate-limit.max-tracked-ips=10000

# Structured Access Logging & MDC Tracing
sip.server.access-log.enabled=true
sip.server.mdc.enabled=true

# DoS Flood Mitigation Limits (RFC 3261 §21.5.2)
sip.server.max-message-size-bytes=65536
sip.server.max-header-count=100
sip.server.max-header-size-bytes=8192

# Session & Dialog Management
sip.server.session-ttl-ms=1800000
sip.server.max-sessions=10000
```

---

## Gradle Commands

### Build & Run All Tests
```bash
./gradlew check test
```

### Run Consuming Application
```bash
./gradlew :sip-app:run
```

### Run NetAnn Announcement Service
```bash
./gradlew :micronaut-netann:run
```

### Build GraalVM Native Executable
```bash
./gradlew :sip-app:nativeCompile
./gradlew :micronaut-netann:nativeCompile
```

### Run Native Executable
```bash
./sip-app/build/native/nativeCompile/sip-app
./micronaut-netann/build/native/nativeCompile/micronaut-netann
```

### Publish Library to Maven Local
```bash
./gradlew :micronaut-sdp:publishToMavenLocal :micronaut-rtp:publishToMavenLocal :micronaut-sip:publishToMavenLocal
```

### Publish Library to GitHub Packages
```bash
./gradlew :micronaut-sdp:publishAllPublicationsToGitHubPackagesRepository :micronaut-rtp:publishAllPublicationsToGitHubPackagesRepository :micronaut-sip:publishAllPublicationsToGitHubPackagesRepository
```

---

## GitHub Actions CI/CD

The workflow in [`.github/workflows/ci.yml`](.github/workflows/ci.yml) automates:
- **Build & Test**: Checks out code, configures Zulu JDK 25 with Gradle cache, and runs `./gradlew check test --info`.
- **Artifact Archiving**: Saves test XML and HTML reports as build artifacts.
- **Publishing**: On pushes to `main`/`master` or new releases, publishes `net.pilgrim:micronaut-sdp`, `net.pilgrim:micronaut-rtp`, and `net.pilgrim:micronaut-sip` to GitHub Packages.

---

## Testing

See [doc/TESTING.md](doc/TESTING.md) for automated test suites and protocol conformance testing.

---

## Contributing & Security

See [doc/CONTRIBUTING.md](doc/CONTRIBUTING.md) for contribution guidelines and [doc/SECURITY.md](doc/SECURITY.md) for the security policy.

---

## Performance & Benchmarking

See [doc/PERFORMANCE.md](doc/PERFORMANCE.md) for SIPp benchmark results and instructions.
