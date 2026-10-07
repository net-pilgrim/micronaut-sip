# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |

## Reporting a Vulnerability

If you discover a security vulnerability within Micronaut SIP, please DO create a public issue.
I want security issues to be visible and addressed by community also.

Please include:
- A description of the vulnerability and its potential impact.
- Steps to reproduce or a proof of concept.
- Affected versions and environments.

We appreciate your efforts to responsibly disclose your findings.

---

## Signaling Security (SIP over TLS / SIPS)

Micronaut SIP provides native transport-layer encryption adhering to **RFC 3261 Section 26** and **RFC 5630**:

### 1. Server TLS Configuration
TLS is configured under the `sip.server` configuration namespace:

```properties
# Enable TLS server listener (default port 5061 per RFC 3261)
sip.server.tls-enabled=true
sip.server.tls.port=5061
sip.server.tls.host=0.0.0.0

# PKCS12 or JKS Keystore
sip.server.key-store-path=/path/to/keystore.p12
sip.server.key-store-password=changeit
sip.server.key-store-type=PKCS12

# Truststore for mutual TLS (mTLS)
sip.server.trust-store-path=/path/to/truststore.p12
sip.server.trust-store-password=changeit
sip.server.trust-store-type=PKCS12

# Require client certificate verification (mTLS)
sip.server.client-auth=false

# Development / testing only: trust all certs and use ephemeral self-signed certificate
# WARNING: Do not enable trust-all in production environments
sip.server.trust-all=false
```

### 2. Client TLS Signaling
The `ReactiveSipClient` automatically upgrades communication to TLS when:
- The Request-URI scheme is `sips:` (e.g. `sips:alice@secure.domain.com`) per RFC 3261 §19.1.
- The Request-URI contains `transport=tls` parameter.
- The outbound request has `Via: SIP/2.0/TLS`.
- RFC 3263 DNS resolution yields a destination with `SipTransport.TLS` (via `SIPS+D2T` NAPTR or `_sips._tcp` SRV records).

### 3. Production Recommendations
- **Always specify strong keystores and truststores**: Do not set `trust-all=true` in production.
- **Enable mutual TLS (`client-auth=true`)** for SIP trunking and internal proxy-to-proxy interconnects.
- **Restrict port accessibility**: Limit ingress exposure on port `5061` to authorized SIP peers or SBCs via firewall/security groups.

