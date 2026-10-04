# Examples

## Example SIP Controller (`CallController`)

In [`sip-app/src/main/java/net/pilgrim/controller/CallController.java`](../sip-app/src/main/java/net/pilgrim/controller/CallController.java):

```java
@SipController
public class CallController {

    private static final SdpNegotiator SDP_NEGOTIATOR = new SdpNegotiator();
    private final RtpMediaManager rtpMediaManager;

    @Inject
    public CallController(RtpMediaManager rtpMediaManager) {
        this.rtpMediaManager = rtpMediaManager != null ? rtpMediaManager : new RtpMediaManager();
    }

    // Handles INVITE: allocates dynamic Netty RTP port and negotiates SDP offer/answer
    @OnInvite
    public Flux<SipResponse> onInvite(SipRequest request,
                                      @SipCallId String callId,
                                      @SipBody String sdpOffer,
                                      SipSession session) {
        session.setState(SipSession.State.EARLY);

        // Allocate dynamic even UDP RTP media port pair
        int localAudioPort = 49170;
        try {
            RtpMediaSession mediaSession = rtpMediaManager.createSession(callId);
            localAudioPort = mediaSession.getLocalPort();
        } catch (Exception ignored) {}

        SipResponse ringing = SipResponse.ringing(request);
        SipResponse ok;

        if (sdpOffer != null && !sdpOffer.isBlank()) {
            // Early offer: client offered SDP in INVITE, server returns answer in 200 OK
            String answer = SDP_NEGOTIATOR.createAnswer(sdpOffer, localAudioPort);
            ok = SipResponse.ok(request, answer, "application/sdp");
        } else {
            // Late offer: server offers SDP in 200 OK, client answers in ACK
            String localOffer = SDP_NEGOTIATOR.createOffer(localAudioPort);
            ok = SipResponse.ok(request, localOffer, "application/sdp");
        }

        return Flux.concat(
            Mono.just(ringing),
            Mono.just(ok).delayElement(Duration.ofMillis(50))
        );
    }

    // Handles ACK: marks dialog CONFIRMED and latches remote RTP destination
    @OnAck
    public void onAck(SipRequest request,
                      @SipCallId String callId,
                      @SipBody String ackSdpAnswer,
                      SipSession session) {
        if (session != null && session.getState() != SipSession.State.TERMINATED) {
            session.setState(SipSession.State.CONFIRMED);
        }
    }

    // Handles BYE: terminates active session and releases Netty RTP port pair
    @OnBye
    public Mono<SipResponse> onBye(SipRequest request,
                                   @SipCallId String callId,
                                   SipSession session) {
        session.setState(SipSession.State.TERMINATED);
        rtpMediaManager.terminateSession(callId);
        return Mono.just(SipResponse.ok(request));
    }

    // Handles CANCEL: aborts in-flight call setup and tears down media session
    @OnCancel
    public void onCancel(SipRequest request,
                         @SipCallId String callId,
                         SipSession session) {
        if (session != null) session.setState(SipSession.State.TERMINATED);
        rtpMediaManager.terminateSession(callId);
    }

    // Handles REGISTER with URI injection and request-URI parameter binding
    @OnRegister
    public SipResponse onRegister(SipRequest request,
                                  @SipTo SipUri toUri,
                                  @SipParam(value = "transport", defaultValue = "udp") String transport,
                                  @SipHeader(value = "Contact", required = false) String contact) {
        SipResponse response = SipResponse.ok(request);
        if (contact != null) response.getHeaders().setContact(contact);
        response.getHeaders().set("X-Registered-User", toUri.getUser());
        response.getHeaders().set("X-Transport-Param", transport);
        response.getHeaders().set(SipHeaders.EXPIRES, "3600");
        return response;
    }

    // Handles OPTIONS capability discovery
    @OnOptions
    public SipResponse onOptions(SipRequest request) {
        SipResponse response = SipResponse.ok(request);
        response.getHeaders().set(SipHeaders.ALLOW, "INVITE, ACK, BYE, CANCEL, OPTIONS, REGISTER, MESSAGE, INFO");
        return response;
    }

    // Handles instant MESSAGE (RFC 3428) with @SipFrom and @SipDtmf injection
    @OnMessage
    public Mono<SipResponse> onMessage(SipRequest request,
                                       @SipFrom String from,
                                       @SipBody String messageBody,
                                       @SipDtmf DtmfSignal dtmf) {
        if (dtmf != null) {
            LOG.info("Received DTMF via MESSAGE from {}: digit='{}'", from, dtmf.getDigit());
        }
        return Mono.just(SipResponse.ok(request));
    }

    // Handles mid-dialog INFO (RFC 2976 / RFC 6086) DTMF relay signaling
    @OnInfo
    public Mono<SipResponse> onInfo(SipRequest request,
                                    @SipCallId String callId,
                                    @SipFrom String from,
                                    @SipDtmf DtmfSignal dtmf,
                                    SipSession session) {
        if (dtmf != null) {
            LOG.info("Received DTMF tone '{}' (duration: {}ms) for call {}",
                    dtmf.getDigit(), dtmf.getDuration(), callId);
            if (session != null) {
                String existing = session.getAttribute("dtmfDigits");
                session.setAttribute("dtmfDigits", (existing != null ? existing : "") + dtmf.getDigit());
            }
            SipResponse ok = SipResponse.ok(request);
            ok.getHeaders().set("X-Received-DTMF", String.valueOf(dtmf.getDigit()));
            return Mono.just(ok);
        }
        return Mono.just(SipResponse.ok(request));
    }
}
```

---

## Example SDP Offer/Answer Negotiation (`micronaut-sdp`)

Using [`SdpNegotiator`](../micronaut-sdp/src/main/java/net/pilgrim/sip/sdp/SdpNegotiator.java) and [`SdpParser`](../micronaut-sdp/src/main/java/net/pilgrim/sip/sdp/SdpParser.java):

```java
SdpNegotiator negotiator = new SdpNegotiator();

// 1. Generate an initial SDP offer for an allocated RTP port (e.g. port 10002)
String offer = negotiator.createOffer(10002);
/*
v=0
o=- 1791052800 1 IN IP4 0.0.0.0
s=MicronautSIP
c=IN IP4 0.0.0.0
t=0 0
m=audio 10002 RTP/AVP 0 8
a=rtpmap:0 PCMU/8000
a=rtpmap:8 PCMA/8000
a=sendrecv
*/

// 2. Generate a matching answer for an incoming offer on a local media port (e.g. port 10004)
String answer = negotiator.createAnswer(offer, 10004);

// 3. Inspect parsed SDP message fields
SdpParser parser = new SdpParser();
SdpMessage message = parser.parse(answer);
int remoteAudioPort = message.getAudioPort();
String direction = message.getDirection(); // "sendrecv", "sendonly", "recvonly", "inactive"
```

---

## Example Reactive RTP Streaming (`micronaut-rtp`)

Using Netty pipeline transport with [`RtpNettySender`](../micronaut-rtp/src/main/java/net/pilgrim/sip/rtp/transport/RtpNettySender.java), [`RtpNettyReceiver`](../micronaut-rtp/src/main/java/net/pilgrim/sip/rtp/transport/RtpNettyReceiver.java), and G.711 codec transcoding:

```java
// 1. Initialize receiver on loopback/bind address
InetSocketAddress bindAddr = new InetSocketAddress("127.0.0.1", 10004);
try (RtpNettyReceiver receiver = new RtpNettyReceiver(bindAddr)) {

    // 2. Reactively subscribe to inbound RTP packets
    receiver.incomingPackets().subscribe(inbound -> {
        RtpPacket packet = inbound.packet();
        System.out.println("Received RTP packet seq=" + packet.getSequenceNumber()
                + " ts=" + packet.getTimestamp()
                + " from " + inbound.senderAddress());
    });

    // 3. Stream 16-bit linear PCM audio frames encoded via G.711 PCMU (payload type 0)
    InetSocketAddress target = new InetSocketAddress("127.0.0.1", receiver.getLocalPort());
    RtpPacketizer packetizer = new RtpPacketizer(0, 8000, 1, 0); // PT 0, 8000 Hz, init seq=1, init ts=0

    try (RtpNettySender sender = new RtpNettySender(target, packetizer)) {
        byte[] pcm16Frame = new byte[320]; // 160 16-bit PCM samples = 20ms frame
        sender.sendPcm16LeFrame(pcm16Frame, new G711UlawCodec(), true)
              .block(Duration.ofSeconds(1));
    }
}
```

---

## Example Reactive Filter (`@SipFilter`)

Implement [`SipServerFilter`](../micronaut-sip/src/main/java/net/pilgrim/sip/filter/SipServerFilter.java) with `@Singleton` or `@SipFilter`:

```java
@Singleton
@SipFilter(methods = {SipMethod.INVITE, SipMethod.REGISTER}, order = -100)
public class CustomSecurityFilter implements SipServerFilter {

    @Override
    public Publisher<SipResponse> doFilter(SipRequest request, SipFilterChain chain) {
        // Inspect or validate request
        if (!request.getHeaders().contains("X-Auth-Token")) {
            return Mono.just(request.createResponse(403, "Forbidden - Missing Auth Token"));
        }

        // Add correlation header to request before passing down
        request.getHeaders().set("X-Correlation-Id", UUID.randomUUID().toString());

        // Process downstream and mutate response reactively
        return Flux.from(chain.proceed(request))
                .map(response -> {
                    response.getHeaders().set("X-Filtered-By", "CustomSecurityFilter");
                    return response;
                });
    }
}
```
