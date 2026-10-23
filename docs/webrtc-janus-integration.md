# WebRTC Integration with Janus Gateway

## Overview

This document describes how to integrate Janus Gateway to provide WebRTC support for the SIP/VXML application.

## Architecture

```
Browser (WebRTC) <--SRTP--> Janus Gateway <--RTP--> SIP/VXML Application
                              |
                              +--SIP--> SIP Stack (micronaut-sip)
```

## Why Janus?

- **Handles WebRTC complexity**: DTLS, SRTP, ICE, STUN/TURN
- **SIP plugin**: Can register as a SIP client and bridge calls
- **Lightweight**: C-based, efficient
- **Well-documented**: Active community support
- **REST/WebSocket API**: Easy to integrate

## Installation

### 1. Install Janus Gateway

```bash
# Ubuntu/Debian
sudo apt-get install libmicrohttpd-dev libjansson-dev \
    libssl-dev libsrtp2-dev libsofia-sip-ua-dev libglib2.0-dev \
    libopus-dev libogg-dev libcurl4-openssl-dev liblua5.3-dev \
    libconfig-dev pkg-config gengetopt libtool automake

git clone https://github.com/meetecho/janus-gateway.git
cd janus-gateway
sh autogen.sh
./configure --prefix=/opt/janus
make
sudo make install
```

### 2. Configure Janus

Edit `/opt/janus/etc/janus/janus.jcfg`:

```json
{
  "general": {
    "configs_folder": "/opt/janus/etc/janus",
    "plugins_folder": "/opt/janus/lib/janus/plugins",
    "transports_folder": "/opt/janus/lib/janus/transports",
    "log_to_stdout": true,
    "debug_level": 4
  },
  "nat": {
    "stun_server": "stun.l.google.com",
    "stun_port": 19302,
    "ice_lite": false,
    "ice_tcp": true
  },
  "media": {
    "rtp_port_range": "20000-40000"
  }
}
```

### 3. Configure Janus SIP Plugin

Edit `/opt/janus/etc/janus/janus.plugin.sip.jcfg`:

```json
{
  "general": {
    "local_ip": "127.0.0.1",
    "keepalive_interval": 30
  },
  "sipre": [
    {
      "id": "vxml-gateway",
      "type": "guest",
      "proxy": "sip:127.0.0.1:5060",
      "register_ttl": 3600
    }
  ]
}
```

### 4. Start Janus

```bash
/opt/janus/bin/janus
```

## Integration Approaches

### Option 1: SIP Bridge (Recommended)

Janus acts as a SIP client and places calls to your SIP server:

```
Browser <--WebRTC--> Janus <--SIP--> Your SIP Server <--> VXML Engine
```

**Pros:**
- Reuses existing SIP infrastructure
- VXML sessions work unchanged
- Standard SIP signaling

**Cons:**
- Requires SIP server configuration
- Extra hop in signaling

### Option 2: Direct RTP Bridge

Janus converts WebRTC to plain RTP and forwards to your application:

```
Browser <--WebRTC--> Janus <--RTP--> micronaut-rtp <--> VXML Engine
```

**Pros:**
- Direct media path
- Lower latency

**Cons:**
- Requires custom signaling integration
- More complex

## Implementation: SIP Bridge Approach

### 1. Browser Client

```javascript
// Connect to Janus
const janus = new Janus({
    server: 'ws://localhost:8188',
    success: function() {
        janus.attach({
            plugin: "janus.plugin.sip",
            success: function(pluginHandle) {
                sipPlugin = pluginHandle;
                
                // Register or make guest call
                const register = {
                    request: "call",
                    uri: "sip:vxml@localhost:5060",
                    headers: {
                        "X-VXML-URL": "/vxml/Main.vxml"
                    }
                };
                
                sipPlugin.send({
                    message: register,
                    jsep: offer  // WebRTC offer
                });
            },
            onmessage: function(msg, jsep) {
                if (jsep) {
                    // Set remote SDP answer
                    sipPlugin.handleRemoteJsep({ jsep: jsep });
                }
            }
        });
    }
});
```

### 2. SIP Server Integration

Your existing SIP server needs to:
1. Accept calls from Janus (SIP client at Janus IP)
2. Extract VXML URL from SIP headers
3. Create VXML session with RTP media

**Modify your SIP INVITE handler:**

```java
@SipMethod("INVITE")
public void onInvite(@SipMessage SipRequest request, SipServerTransaction transaction) {
    // Extract VXML URL from custom header
    String vxmlUrl = request.getHeader("X-VXML-URL");
    if (vxmlUrl == null) {
        vxmlUrl = "classpath:vxml/Main.vxml";
    }
    
    // Parse SDP to get RTP port
    SdpParser sdp = new SdpParser(request.getBody());
    String remoteHost = sdp.getConnectionAddress();
    int remotePort = sdp.getMediaPort("audio");
    
    // Create RTP session
    RtpMediaSession rtpSession = rtpMediaManager.createSession();
    int localPort = rtpSession.getLocalPort();
    
    // Create VXML session with RTP media
    VxmlMedia media = new RtpVxmlMedia(rtpSession);
    VxmlSession vxmlSession = new VxmlSession(
        documentLoader.loadDocument(vxmlUrl),
        media,
        ttsClient,
        audioLoader
    );
    
    // Connect RTP to Janus
    rtpSession.connect(remoteHost, remotePort);
    
    // Send 200 OK with SDP answer
    String answerSdp = createSdpAnswer(localPort);
    SipResponse ok = createResponse(200, "OK", request);
    ok.setBody(answerSdp);
    transaction.sendResponse(ok);
    
    // Start VXML session
    vxmlSession.start();
}
```

### 3. Create RtpVxmlMedia Implementation

```java
public class RtpVxmlMedia implements VxmlMedia {
    private final RtpMediaSession rtpSession;
    private Consumer<Character> dtmfListener;
    
    public RtpVxmlMedia(RtpMediaSession rtpSession) {
        this.rtpSession = rtpSession;
        
        // Listen for DTMF from RTP
        rtpSession.setDtmfHandler(digit -> {
            if (dtmfListener != null) {
                dtmfListener.accept(digit);
            }
        });
    }
    
    @Override
    public void playAudio(byte[] pcm, Runnable onFinished) {
        // Send audio over RTP
        rtpSession.sendAudio(pcm, onFinished);
    }
    
    @Override
    public void setDtmfListener(Consumer<Character> listener) {
        this.dtmfListener = listener;
    }
    
    @Override
    public void setBargeIn(boolean allowed) {
        // Can implement barge-in detection in RTP layer
    }
    
    // ... other methods
}
```

## Testing

### 1. Start Services

```bash
# Terminal 1: Start Janus
/opt/janus/bin/janus

# Terminal 2: Start your SIP/VXML application
./gradlew :sip-app:run
```

### 2. Open Browser

Navigate to your web client that uses the Janus JavaScript library to make a WebRTC call.

### 3. Verify Call Flow

```
Browser -> Janus: WebRTC offer (DTLS/SRTP)
Janus -> SIP: INVITE sip:vxml@localhost (plain RTP)
SIP -> VXML: Creates session
VXML -> TTS: Synthesizes prompts
SIP -> Janus: RTP audio packets
Janus -> Browser: SRTP audio packets (encrypted)
Browser -> Janus: DTMF (via data channel or audio)
Janus -> SIP: DTMF (RFC 4733 or SIP INFO)
SIP -> VXML: Processes DTMF
```

## Troubleshooting

### No Audio

- Check firewall allows RTP ports (20000-40000)
- Verify Janus is receiving RTP from your app: `tcpdump -i lo udp port 20000-40000`
- Check codec negotiation in SDP (must support PCMU/PCMA)

### DTMF Not Working

- Ensure Janus forwards DTMF as RFC 4733 RTP events
- Check your RTP stack handles RFC 4733
- Alternative: Send DTMF as SIP INFO messages

### Connection Fails

- Check Janus logs: `/var/log/janus.log` or stdout
- Verify ICE candidates are being exchanged
- May need TURN server for restrictive networks

## Production Considerations

### Security

- Use HTTPS/WSS for Janus API
- Configure TURN server with authentication
- Restrict SIP access to Janus IP only

### Scaling

- Run multiple Janus instances behind load balancer
- Use Redis for session state sharing
- Monitor RTP port exhaustion

### Monitoring

- Track active WebRTC sessions
- Monitor RTP packet loss and jitter
- Alert on failed DTLS handshakes

## Alternative: Using Janus VideoCall Plugin

If you don't want to use SIP, you can use the VideoCall plugin and handle signaling via Janus REST API:

1. Browser connects to Janus VideoCall plugin
2. Your backend polls Janus API for new calls
3. Backend allocates RTP ports and updates Janus
4. Your RTP stack connects to Janus ports directly
5. VXML session handles media

This avoids SIP but requires more custom signaling code.

## References

- [Janus Gateway Documentation](https://janus.conf.meetecho.com/docs/)
- [Janus SIP Plugin](https://janus.conf.meetecho.com/docs/sip.html)
- [WebRTC for the Curious](https://webrtcforthecurious.com/)
- [RFC 8834: WebRTC Media Transport](https://datatracker.ietf.org/doc/html/rfc8834)
