# Kubernetes Deployment Guide for Micronaut SIP Platform

This directory contains production-ready Kubernetes manifests for deploying the **Micronaut SIP Platform**, including both the primary application (`sip-app`) and the RFC 4240 NetAnn announcement media server (`micronaut-netann`).

## Directory Layout

```
k8s/
├── namespace.yaml                  # Dedicated 'sip-system' namespace
├── kustomization.yaml              # Kustomize manifest aggregating all resources
├── sip-app/
│   ├── configmap.yaml              # Configuration properties for sip-app
│   ├── deployment.yaml             # 2-replica Deployment with health/liveness probes
│   └── service.yaml                # LoadBalancer Service exposing 5060 (UDP/TCP) & 8080 (HTTP)
└── micronaut-netann/
    ├── configmap.yaml              # Configuration properties for NetAnn
    ├── deployment.yaml             # 2-replica Deployment with prompt storage volume
    └── service.yaml                # LoadBalancer Service exposing 5060 (UDP/TCP) & 8080 (HTTP)
```

---

## Architecture & Networking Considerations

SIP and RTP services have specific networking characteristics in Kubernetes:

### 1. Signaling (Port 5060 UDP & TCP)
- **Session Affinity**: Both services configure `sessionAffinity: ClientIP` with a 3-hour cache (`10800s`). This ensures that SIP transactions and in-dialog requests (`PRACK`, `ACK`, `BYE`, `INFO`) originating from the same client IP land consistently on the same pod.
- **Service Types**:
  - `LoadBalancer`: Standard cloud deployment (AWS NLB, GCP Network Load Balancer, Azure Standard LB) supporting multi-protocol (UDP and TCP) load balancing.
  - `NodePort`: Suitable for bare-metal or on-premises edge SBC routing.
  - `hostNetwork: true`: For ultra-low latency or large dynamic RTP port ranges without kube-proxy SNAT overhead.

### 2. Media Streaming & Advertised IP Resolution (RTP UDP Ports 10000–20000)
VoIP media servers negotiate dynamic UDP ports per active call in SDP offer/answer exchanges (`m=audio <port> RTP/AVP ...` and `c=IN IP4 <ip>`). In Kubernetes, dynamic RTP media traversal requires special architectural handling:

1. **Host Network Mode (`hostNetwork: true` & `dnsPolicy: ClusterFirstWithHostNet`)**:
   - Both `sip-app` and `micronaut-netann` deployments configure `hostNetwork: true`. This binds the container directly to the host node's network interfaces, eliminating `kube-proxy` SNAT/DNAT overhead and allowing direct packet transmission across the full dynamic RTP port range (`10000–20000`).
   - `dnsPolicy: ClusterFirstWithHostNet` preserves internal Kubernetes CoreDNS resolution while operating on the host network.

2. **Advertised IP via Downward API (`SIP_SERVER_ADVERTISED_IP`)**:
   - When a pod sends an SDP answer or a SIP provisional/success response with a `Contact` header, it must NOT advertise `127.0.0.1` or internal container IP addresses to remote external clients.
   - The deployment manifests inject the host node's external/routable IP using the Kubernetes Downward API:
     ```yaml
     - name: SIP_SERVER_ADVERTISED_IP
       valueFrom:
         fieldRef:
           fieldPath: status.hostIP
     ```
   - Both `CallController` and `AnnouncementController` resolve `sip.server.advertised-ip` (falling back to host IP or non-loopback interface) and insert this routable address into:
     - The `Contact` header: `<sip:<advertised-ip>:<server-port>>` (using the server's listening port, NOT the client's remote port).
     - The SDP connection line: `c=IN IP4 <advertised-ip>`.
     - The SDP origin line: `o=MicronautSIP ... IN IP4 <advertised-ip>`.
     - In-dialog `BYE` requests: `Via: SIP/2.0/UDP <advertised-ip>:<server-port>;branch=...`.

3. **Service Port Range & Symmetric RTP Latching**:
   - The `Service` manifests declare representative media ports (`10000`, `10002`, `10004`, `10006`, `10008`, `10010`) to facilitate cloud firewall/security group port discovery.
   - The media manager uses symmetric RTP latching (RFC 4961): when the remote endpoint emits its first RTP packet to the negotiated local RTP port, the server automatically latches onto the remote client's source IP and port.

### 3. Observability & Health Probes (Port 8080 TCP)
- **Liveness Probe**: `HTTP GET :8080/health/liveness`
- **Readiness Probe**: `HTTP GET :8080/health/readiness`
- **Startup Probe**: `HTTP GET :8080/health`
- **Prometheus Metrics**: `HTTP GET :8080/prometheus` (automatically annotated for Prometheus Operator scraping).

---

## Deployment Instructions

### 1. Build and Push Container Images

#### Option A: Build with Gradle (JVM Base)
```bash
./gradlew :sip-app:dockerBuild :micronaut-netann:dockerBuild
```

#### Option B: Build GraalVM Native Image Containers (Ultra-low latency, ~20ms startup, ~45MB RSS)
```bash
./gradlew :sip-app:dockerBuildNative :micronaut-netann:dockerBuildNative
```

### 2. Deploy with Kustomize

Apply the entire stack with a single command:
```bash
kubectl apply -k k8s/
```

Or deploy individual applications:
```bash
# Create namespace
kubectl apply -f k8s/namespace.yaml

# Deploy sip-app
kubectl apply -f k8s/sip-app/

# Deploy micronaut-netann
kubectl apply -f k8s/micronaut-netann/
```

### 3. Verify Deployment

Check that all pods and services are healthy:
```bash
kubectl get pods,svc -n sip-system
```

Check the health status:
```bash
kubectl port-forward svc/sip-app 8080:8080 -n sip-system &
curl -i http://localhost:8080/health
```

Output:
```json
{
  "status": "UP",
  "components": {
    "sipServer": {
      "status": "UP",
      "details": {
        "udpPort": 5060,
        "tcpPort": 5060,
        "activeSessions": 0
      }
    }
  }
}
```

Check NetAnn health:
```bash
kubectl port-forward svc/micronaut-netann 8081:8080 -n sip-system &
curl -i http://localhost:8081/health
```

---

## Test Signaling via SIPp

Run a test call against the deployed `sip-app` LoadBalancer IP:
```bash
sipp -sn uac -s 100 <EXTERNAL-IP>:5060 -m 10 -l 1
```

Run a test announcement against `micronaut-netann`:
```bash
sipp -sn uac -s annc <EXTERNAL-IP>:5060 -m 1 -l 1
```
