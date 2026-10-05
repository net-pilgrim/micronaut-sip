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

### 2. Media Streaming (RTP UDP Ports 10000–20000)
- The media modules use symmetric RTP latching (RFC 4961). The server binds dynamic even UDP ports within `rtp.media.min-port` (10000) to `rtp.media.max-port` (20000) and dynamically latches the remote client's media address upon receiving the first inbound UDP packet.

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
