# Janus Gateway + VXML Integration Guide

This guide explains how to integrate Janus Gateway with your VXML interpreter to enable WebRTC-to-SIP-to-VXML call flows.

## Architecture Overview

```
WebRTC Browser <--> Janus Gateway <--> Your SIP Server <--> VXML Interpreter
                    (WebRTC/SIP)         (SIP/RTP)            (RTP/VXML)
```

1. **Browser**: Makes WebRTC calls using JavaScript
2. **Janus Gateway**: Converts WebRTC to SIP
3. **Your SIP Server**: Receives SIP INVITE and creates RTP session
4. **VXML Interpreter**: Executes VXML scripts and streams audio via RTP

## Components Created

### 1. Janus Configuration Files
- `janus-configs/janus.jcfg` - Main Janus configuration
- `janus-configs/janus.plugin.sip.jcfg` - SIP plugin configuration
- `janus-configs/janus.transport.websockets.jcfg` - WebSocket transport

### 2. RtpVxmlMedia Bridge
- `micronaut-vxml/src/main/java/net/pilgrim/vxml/media/RtpVxmlMedia.java`
- Implements VxmlMedia interface using RTP transport
- Handles audio playback, DTMF detection, and barge-in

### 3. Janus SIP Controller
- `sip-app/src/main/java/net/pilgrim/controller/JanusVxmlController.java`
- Handles SIP INVITE from Janus
- Creates RTP session and bridges to VXML
- Manages call lifecycle (INVITE, ACK, BYE, CANCEL)

### 4. Janus Client Library
- `sip-app/src/main/java/net/pilgrim/janus/JanusClient.java`
- WebSocket client for controlling Janus
- Creates sessions, attaches plugins, makes calls

## Installation Steps

### 1. Install Janus Gateway

Already completed. Janus is installed at `/opt/janus`.

### 2. Install Janus Configurations

```bash
cd /home/dsv/work/sip
./install-janus-configs.sh
```

### 3. Start Janus Gateway

```bash
/opt/janus/bin/janus
```

Janus will:
- Listen on WebSocket port 8188 for browser connections
- Use SIP plugin to make calls to your SIP server
- Forward RTP media between WebRTC and SIP

### 4. Configure Your SIP Server

Make sure your SIP server is listening on port 5060 (UDP/TCP).
The JanusVxmlController will handle INVITE requests from Janus.

## Usage Flow

### Browser to VXML Call Flow

1. **Browser initiates WebRTC call**:
   ```javascript
   // Create offer
   const offer = await peerConnection.createOffer();
   await peerConnection.setLocalDescription(offer);
   
   // Send to Janus via WebSocket
   janusClient.makeCall('sip:welcome@localhost:5060', offer.sdp);
   ```

2. **Janus converts WebRTC to SIP**:
   - Janus receives WebRTC offer
   - Sends SIP INVITE to your server at localhost:5060
   - SDP contains RTP connection details

3. **Your SIP server receives INVITE**:
   - `JanusVxmlController.onInvite()` is called
   - Creates `RtpMediaSession` for audio
   - Creates `RtpVxmlMedia` bridge
   - Starts VXML session with `VxmlSessionFactory.createSession()`
   - Returns 200 OK with SDP answer

4. **VXML session executes**:
   - VXML interpreter runs the document
   - Audio is streamed via `RtpVxmlMedia.playAudio()`
   - DTMF digits are captured via `RtpMediaSession.addDtmfListener()`
   - Barge-in interrupts audio playback

5. **Call ends**:
   - Browser or VXML session hangs up
   - Janus sends BYE to your SIP server
   - `JanusVxmlController.onBye()` cleans up resources

## VXML Document Routing

The `JanusVxmlController` determines which VXML document to execute based on:

1. **X-VXML-URL header** in SIP INVITE (highest priority)
2. **To URI user part** (e.g., `sip:welcome@localhost` → `/vxml/welcome.vxml`)
3. **Default fallback** (`/vxml/welcome.vxml`)

### Example: Custom VXML URL

Configure Janus to add a custom header:

```json
// In Janus SIP call request
{
  "request": "call",
  "uri": "sip:app@localhost:5060",
  "headers": {
    "X-VXML-URL": "http://localhost:8080/vxml/custom-app.vxml"
  }
}
```

## Browser WebRTC Client Example

```javascript
// Connect to Janus
const ws = new WebSocket('ws://localhost:8188');
let sessionId, handleId;

ws.onopen = async () => {
  // Create Janus session
  const createResp = await sendJanus({
    janus: 'create',
    transaction: randomString()
  });
  sessionId = createResp.data.id;

  // Attach SIP plugin
  const attachResp = await sendJanus({
    janus: 'attach',
    plugin: 'janus.plugin.sip',
    session_id: sessionId,
    transaction: randomString()
  });
  handleId = attachResp.data.id;

  // Create WebRTC peer connection
  const pc = new RTCPeerConnection();
  
  // Add audio track
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  stream.getTracks().forEach(track => pc.addTrack(track, stream));

  // Create offer
  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);

  // Make SIP call through Janus
  await sendJanus({
    janus: 'message',
    session_id: sessionId,
    handle_id: handleId,
    transaction: randomString(),
    body: {
      request: 'call',
      uri: 'sip:welcome@localhost:5060'
    },
    jsep: {
      type: 'offer',
      sdp: offer.sdp
    }
  });

  // Listen for answer
  ws.onmessage = async (event) => {
    const msg = JSON.parse(event.data);
    if (msg.jsep && msg.jsep.type === 'answer') {
      await pc.setRemoteDescription(new RTCSessionDescription(msg.jsep));
    }
  };
};

function sendJanus(msg) {
  return new Promise((resolve) => {
    const txn = msg.transaction;
    ws.send(JSON.stringify(msg));
    
    const handler = (event) => {
      const resp = JSON.parse(event.data);
      if (resp.transaction === txn) {
        ws.removeEventListener('message', handler);
        resolve(resp);
      }
    };
    ws.addEventListener('message', handler);
  });
}
```

## Testing

### 1. Start your SIP server
```bash
./gradlew :sip-app:run
```

### 2. Start Janus
```bash
/opt/janus/bin/janus
```

### 3. Open browser console and run the WebRTC client code above

### 4. Monitor logs
- **Janus logs**: See Janus terminal for SIP signaling
- **SIP server logs**: See JanusVxmlController logs for INVITE handling
- **VXML logs**: See VxmlSession logs for interpreter execution

## Troubleshooting

### Janus won't start
- Check logs: `/opt/janus/bin/janus -d 7` (debug mode)
- Verify all config files are in `/opt/janus/etc/janus/`
- Check port 8188 is not already in use

### No audio in call
- Verify RTP ports are open (20000-40000)
- Check SDP negotiation in logs
- Verify codec compatibility (PCMU/PCMA)
- Check firewall rules

### DTMF not detected
- RFC 4733 telephone-event must be negotiated in SDP
- Check payload type matches between Janus and your server
- Enable inband DTMF detection as fallback

### VXML session doesn't start
- Check VXML document URL is accessible
- Verify VxmlSessionFactory is configured
- Check RtpVxmlMedia is properly bridged

## Advanced Configuration

### NAT Traversal
If Janus is behind NAT, configure in `janus.jcfg`:
```
nat: {
  nat_1_1_mapping = "your.public.ip"
  stun_server = "stun.l.google.com"
  stun_port = 19302
}
```

### TURN Server (for restrictive firewalls)
Configure in `janus.jcfg`:
```
nat: {
  turn_server = "turn:turnserver.example.com:3478"
  turn_user = "username"
  turn_pwd = "password"
}
```

### TLS/WSS (for production)
Enable secure WebSocket in `janus.transport.websockets.jcfg`:
```
general: {
  wss = true
  wss_port = 8989
  wss_interface = "0.0.0.0"
}
```

## Performance Tuning

### RTP Buffer Size
Adjust in RtpMediaSession for lower latency:
```java
rtpSession.setJitterBufferMs(20); // Default is 50ms
```

### Audio Frame Size
Smaller frames = lower latency, higher CPU:
```java
rtpSession.setFrameSizeMs(20); // Default is 20ms
```

### Connection Pooling
Reuse RTP sessions when possible:
```java
rtpMediaManager.getOrCreateSession(callId);
```

## Security Considerations

1. **Authenticate SIP requests**: Add authentication to JanusVxmlController
2. **Validate SDP**: Reject malformed SDP offers
3. **Rate limiting**: Limit INVITE requests per IP
4. **TLS/SRTP**: Use encrypted transport in production
5. **VXML URL validation**: Whitelist allowed VXML document URLs

## Next Steps

1. **Create VXML applications**: Design VXML documents for your use cases
2. **Add call recording**: Use RtpMediaSession.startRecording()
3. **Implement conferencing**: Multiple RTP sessions to single VXML session
4. **Add speech recognition**: Integrate ASR with VxmlMedia audio input
5. **Monitor calls**: Expose metrics via Micronaut management endpoints

## References

- [Janus Gateway Documentation](https://janus.conf.meetecho.com/docs/)
- [VoiceXML 2.1 Specification](https://www.w3.org/TR/voicexml21/)
- [RFC 3261 - SIP](https://tools.ietf.org/html/rfc3261)
- [RFC 3550 - RTP](https://tools.ietf.org/html/rfc3550)
- [RFC 4733 - DTMF](https://tools.ietf.org/html/rfc4733)
