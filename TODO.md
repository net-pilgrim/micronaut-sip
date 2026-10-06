# TODO (prioritized: low-hanging fruits first)

This backlog is based on current implementation gaps visible in `micronaut-sip`, `micronaut-rtp`, and `sip-app`.

## 1) Low-hanging fruits (high value, small scope)

1. ~~**Add first-class annotations for already-modeled SIP methods (`PRACK`, `SUBSCRIBE`, `NOTIFY`, `REFER`, `UPDATE`)**~~ [DONE]
   - **Why:** `SipMethod` already includes these methods, but only `INVITE/BYE/ACK/CANCEL/REGISTER/OPTIONS/MESSAGE/INFO` have dedicated annotations.
   - **Where:** `micronaut-sip/src/main/java/net/pilgrim/sip/model/SipMethod.java`, `.../annotation/On*.java`, `.../router/SipDispatcher.java`.
   - **Done when:** New `@OnPrack`, `@OnSubscribe`, `@OnNotify`, `@OnRefer`, `@OnUpdate` (and `@OnPublish`) annotations exist and routes auto-register cleanly without nested if-else ladders.

2. **Improve transport behavior for `TLS/WS/WSS` in client/server**
   - **Why:** Transport enum and parser recognize `TLS/WS/WSS`, but request sending only has explicit branches for UDP/TCP.
   - **Where:** `.../model/SipTransport.java`, `.../client/ReactiveSipClient.java`, `.../transport/SipNettyServer.java`.
   - **Done when:** Either (a) explicit “not supported yet” errors are returned for these transports, or (b) one of them is implemented end-to-end.

3. **Add sample handlers + tests for one new method family (`SUBSCRIBE/NOTIFY`)**
   - **Why:** Integration tests currently assert `SUBSCRIBE` returns `405`; adding one real flow quickly demonstrates extensibility.
   - **Where:** `sip-app/src/main/java/net/pilgrim/controller/CallController.java`, `sip-app/src/test/java/net/pilgrim/sip/SipIntegrationTest.java`.
   - **Done when:** A `SUBSCRIBE` request can establish a basic subscription and `NOTIFY` can be emitted/validated in tests.

4. **Make REGISTER behavior stateful (in-memory registrar with `Expires`)**
   - **Why:** REGISTER currently returns static success headers; no contact store/lifecycle exists.
   - **Where:** `sip-app/src/main/java/net/pilgrim/controller/CallController.java`, new registrar component in `micronaut-sip` or `sip-app`.
   - **Done when:** REGISTER adds/refreshes/removes contact bindings (`Expires: 0` removes), and lookup is test-covered.

## 2) Medium effort, strong protocol value

5. ~~**Reliable provisional responses (`100rel`) and `PRACK` transaction support (RFC 3262)**~~ [DONE]
   - **Why:** `CallController` advertises `Supported: ... 100rel`, but no PRACK flow is implemented.
   - **Where:** `sip-app/src/main/java/net/pilgrim/controller/CallController.java`, `micronaut-sip` routing/transaction logic.
   - **Done when:** `Require: 100rel` + `RSeq` + `PRACK` handshake works over UDP/TCP with integration tests.

6. **Digest authentication (`401/407`) helpers and filter**
   - **Why:** Header/status constants already exist (`Authorization`, `WWW-Authenticate`, 401/407), but no auth challenge/verification pipeline exists.
   - **Where:** `micronaut-sip/src/main/java/net/pilgrim/sip/model/SipHeaders.java`, `.../model/SipStatus.java`, `.../filter/`.
   - **Done when:** Reusable filter can challenge unauthenticated requests and validate digest responses.

7. **Route set support (`Record-Route`/`Route`) for stricter in-dialog routing**
   - **Why:** Header constants exist, but route-set behavior is not implemented as a full dialog feature.
   - **Where:** `.../model/SipHeaders.java`, `.../session/`, `.../router/`.
   - **Done when:** Initial dialog stores route set and subsequent in-dialog requests follow it.

## 3) Larger bets (high impact, broader scope)

8. **Implement RTCP companion stack + media quality metrics**
   - **Why:** RTP port manager reserves RTCP pairs, but no RTCP sender/receiver/reporting pipeline exists.
   - **Where:** `micronaut-rtp/src/main/java/net/pilgrim/sip/rtp/media/MediaPortManager.java`, new `rtcp` transport/model components.
   - **Done when:** Sender/Receiver Reports are exchanged and surfaced through metrics.

9. **Presence/event framework (`SUBSCRIBE/NOTIFY` packages) with expiry and refresh**
   - **Why:** Enables real SIP eventing use-cases (presence, message-summary, dialog events).
   - **Where:** `micronaut-sip` router/session modules + `sip-app` sample controller.
   - **Done when:** Event package registry, subscription state machine, and refresh/termination flows are test-covered.

10. **Secure transports end-to-end (`SIPS` over TLS and optional WebSocket signaling)**
   - **Why:** Completes the transport model and enables modern deployment topologies.
   - **Where:** `micronaut-sip` transport layer + config + integration tests.
   - **Done when:** TLS listener/client are production-ready (cert config, handshake, integration tests), then WS/WSS if desired.

## 4) NetAnn VXML-lite track (new)

11. **Add a separate VXML controller behind a dedicated NetAnn profile/property**
   - **Why:** Keep RFC 4240 announcement behavior stable while introducing VXML incrementally.
   - **Where:** new `micronaut-netann` controller (e.g. `VxmlController`) + `@Requires(env=...)` or `@Requires(property=...)` gating.
   - **Done when:** Existing `@OnInvite("annc")` flow is untouched and VXML traffic is isolated to the new controller.

12. **Implement minimal VoiceXML 2.1 interpreter (DTMF-first) from grammar/schema subset**
   - **Why:** Fastest path to practical IVR/dialog support without full external interpreter coupling.
   - **Where:** new parser/runtime components in `micronaut-netann` (document loader, AST, interpreter session state bound to Call-ID).
   - **Done when:** Supports core subset (`form/field/prompt/choice/goto/if`, basic grammars) and executes dialogs over current SIP/RTP stack.

13. **Add MRCP adapter layer for ASR/TTS backends (Whisper for ASR, separate TTS engine)**
   - **Why:** Preserve interpreter neutrality while enabling model/provider swaps.
   - **Where:** `micronaut-netann` service interfaces (e.g. `AsrClient`, `TtsClient`) + MRCP implementation module.
   - **Done when:** Interpreter calls abstract ASR/TTS services; MRCP implementation can target Whisper-compatible ASR and an explicit TTS backend.
