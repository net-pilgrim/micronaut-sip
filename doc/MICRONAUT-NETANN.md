# RFC 4240 NetAnn Announcement Service: Architecture & Call Flows

See the root documentation file [`micronaut-netann.md`](../micronaut-netann.md) for the complete reference architecture, sequence diagrams, SIP signaling traces, error semantics, GraalVM compilation, and Kubernetes deployment instructions.

### Quick Reference

- **Standard**: [RFC 4240: Basic Network Media Services with SIP](https://datatracker.ietf.org/doc/html/rfc4240)
- **Application Module**: `:micronaut-netann`
- **Controller**: [`AnnouncementController`](../micronaut-netann/src/main/java/net/pilgrim/netann/controller/AnnouncementController.java)
- **Audio Loader**: [`AnnouncementAudioLoader`](../micronaut-netann/src/main/java/net/pilgrim/netann/service/AnnouncementAudioLoader.java)
- **RTP Streamer**: [`AnnouncementPlayer`](../micronaut-netann/src/main/java/net/pilgrim/netann/service/AnnouncementPlayer.java)
- **Kubernetes Manifests**: [`k8s/micronaut-netann/`](../k8s/micronaut-netann/)
- **Integration Test Suite**: [`AnnouncementIntegrationTest`](../micronaut-netann/src/test/java/net/pilgrim/netann/AnnouncementIntegrationTest.java)
- **Performance Benchmark**: [1-Hour Sustained Load Benchmark (180,000 calls @ 50 cps, ~100 concurrent streams)](PERFORMANCE.md#1-hour-sustained-load--concurrency-benchmark-micronaut-netann)

### Key Call Flows Documented

1. **Flow 1: Standard Announcement Playback & Server Auto-BYE Teardown** (Full sequence & wire trace)
2. **Flow 2: Caller Early Teardown (`BYE` during streaming)**
3. **Flow 3: Call Setup Cancellation (`CANCEL`)**
4. **Flow 4: Repeat Loop with Inter-Prompt Delay (`repeat=3;delay=1000`)**
5. **Flow 5: Continuous Loop (`repeat=forever`)**
6. **Flow 6: Duration-Capped Playback (`duration=5000`)**
7. **Flow 7: Error Conditions (400, 404, 488)**
8. **Flow 8: Capability Query (`OPTIONS`)**
9. **Flow 9: Persistent TCP Signaling Transport**
