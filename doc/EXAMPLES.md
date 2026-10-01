# Examples

## Example Controller

In [`sip-app/src/main/java/net/pilgrim/controller/CallController.java`](../sip-app/src/main/java/net/pilgrim/controller/CallController.java):

```java
@SipController
public class CallController {

    // Emits 180 Ringing immediately, then 200 OK with SDP after 50ms
    @OnInvite
    public Flux<SipResponse> onInvite(SipRequest request,
                                      @SipCallId String callId,
                                      @SipBody String sdpOffer,
                                      SipSession session) {
        session.setState(SipSession.State.EARLY);

        SipResponse ringing = SipResponse.ringing(request);
        SipResponse ok = SipResponse.ok(request, "v=0\r\no=MicronautSIP...", "application/sdp");

        return Flux.concat(
            Mono.just(ringing),
            Mono.just(ok).delayElement(Duration.ofMillis(50))
        );
    }

    // Handles ACK confirmation (void return type: no response sent per RFC 3261 §17.2.1)
    @OnAck
    public void onAck(SipRequest request,
                      @SipCallId String callId,
                      SipSession session) {
        session.setState(SipSession.State.CONFIRMED);
    }

    // Handles BYE to terminate the call
    @OnBye
    public Mono<SipResponse> onBye(SipRequest request,
                                   @SipCallId String callId,
                                   SipSession session) {
        session.setState(SipSession.State.TERMINATED);
        return Mono.just(SipResponse.ok(request));
    }

    // Handles CANCEL to abort in-flight call setup (RFC 3261 §9)
    @OnCancel
    public void onCancel(SipRequest request,
                         @SipCallId String callId,
                         SipSession session) {
        if (session != null) session.setState(SipSession.State.TERMINATED);
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
        response.getHeaders().set(SipHeaders.ALLOW, "INVITE, ACK, BYE, CANCEL, OPTIONS, REGISTER, MESSAGE");
        return response;
    }

    // Handles instant MESSAGE (RFC 3428) with @SipFrom injection
    @OnMessage
    public Mono<SipResponse> onMessage(SipRequest request,
                                       @SipFrom String from,
                                       @SipBody String messageBody) {
        return Mono.just(SipResponse.ok(request));
    }

    // Declarative error handling mapping custom exceptions to SIP status codes
    @SipError(UserNotFoundException.class)
    public SipResponse handleNotFound(UserNotFoundException ex, SipRequest req) {
        return req.createResponse(404, "User Not Found: " + ex.getMessage());
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
