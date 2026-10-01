# Contributing to Micronaut SIP

Thank you for your interest in contributing to **Micronaut SIP**!

## Development Setup

### Prerequisites
- **JDK 21+** (JDK 25 recommended)
- **Gradle 9+** (or use `./gradlew`)
- Optional: **GraalVM Native Image** for native binary builds

### Build & Run Tests
```bash
./gradlew check
```

To run individual tests:
```bash
./gradlew test --tests "net.pilgrim.sip.*"
```

## Guidelines

1. **RFC 3261 Conformance**: Ensure any protocol changes adhere strictly to RFC 3261 (SIP: Session Initiation Protocol) and related RFCs (e.g. RFC 3264 SDP, RFC 3428 MESSAGE).
2. **Zero-Reflection & Native Image Ready**: We avoid Java reflection at dispatch time. Controller methods must be discovered and invoked via Micronaut's `ExecutableMethod` infrastructure.
3. **Reactive Pipelines**: Handlers should remain non-blocking. Use Project Reactor (`Mono`, `Flux`) for asynchronous and streaming operations.
4. **Code Style**: Follow standard Java conventions. Keep documentation up to date.
