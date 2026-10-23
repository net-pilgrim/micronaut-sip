package net.pilgrim.webrtc;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

@Controller("/webrtc")
public class WebRtcController {

    @Get
    @Produces("text/html")
    public String index() {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>WebRTC VXML Client</title>
    <style>
        body { font-family: sans-serif; max-width: 800px; margin: 50px auto; }
        input { padding: 8px; margin: 5px 0; width: 100%; box-sizing: border-box; }
        button { padding: 10px 20px; margin: 5px; cursor: pointer; }
        button:disabled { opacity: 0.5; cursor: not-allowed; }
        #status { margin: 20px 0; padding: 10px; border-radius: 4px; }
        #log { margin-top: 20px; padding: 10px; background: #f5f5f5; font-family: monospace; font-size: 12px; max-height: 300px; overflow-y: auto; }
        .dtmf-pad { display: grid; grid-template-columns: repeat(3, 1fr); gap: 5px; max-width: 200px; margin: 10px 0; }
        .dtmf-pad button { width: 100%; }
        #videoContainer { margin: 20px 0; border: 1px solid #ccc; border-radius: 4px; overflow: hidden; display: none; }
        #videoDisplay { max-width: 100%; height: auto; display: block; }
    </style>
</head>
<body>
    <h1>WebRTC VXML Client</h1>

    <div>
        <label>VXML Script URL:</label>
        <input id="vxmlUrl" type="text" value="vxml/sample_menu.vxml" placeholder="Enter VXML URL">
    </div>

    <div>
        <button id="callBtn" onclick="startCall()">Start Call</button>
        <button id="hangupBtn" onclick="hangup()" disabled>Hang Up</button>
    </div>

    <div class="dtmf-pad">
        <button onclick="sendDtmf('1')" disabled class="dtmf">1</button>
        <button onclick="sendDtmf('2')" disabled class="dtmf">2</button>
        <button onclick="sendDtmf('3')" disabled class="dtmf">3</button>
        <button onclick="sendDtmf('4')" disabled class="dtmf">4</button>
        <button onclick="sendDtmf('5')" disabled class="dtmf">5</button>
        <button onclick="sendDtmf('6')" disabled class="dtmf">6</button>
        <button onclick="sendDtmf('7')" disabled class="dtmf">7</button>
        <button onclick="sendDtmf('8')" disabled class="dtmf">8</button>
        <button onclick="sendDtmf('9')" disabled class="dtmf">9</button>
        <button onclick="sendDtmf('*')" disabled class="dtmf">*</button>
        <button onclick="sendDtmf('0')" disabled class="dtmf">0</button>
        <button onclick="sendDtmf('#')" disabled class="dtmf">#</button>
    </div>

    <div id="status"></div>

    <div id="videoContainer">
        <img id="videoDisplay" alt="Video display">
    </div>

    <div id="log"></div>

    <script>
        let ws, pc, localStream, dataChannel;
        let audioContext = null;
        let audioQueue = [];
        let isPlaying = false;
        const config = { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] };

        function log(msg) {
            const logDiv = document.getElementById('log');
            logDiv.innerHTML += new Date().toISOString().substr(11, 8) + ' ' + msg + '\\n';
            logDiv.scrollTop = logDiv.scrollHeight;
        }

        function setStatus(msg, color = '#ddd') {
            const status = document.getElementById('status');
            status.textContent = msg;
            status.style.background = color;
        }

        async function startCall() {
            const vxmlUrl = document.getElementById('vxmlUrl').value;
            if (!vxmlUrl) {
                alert('Enter VXML URL');
                return;
            }

            document.getElementById('callBtn').disabled = true;
            document.getElementById('hangupBtn').disabled = false;
            document.querySelectorAll('.dtmf').forEach(btn => btn.disabled = false);
            log('Starting call with VXML: ' + vxmlUrl);
            setStatus('Connecting...', '#ffffcc');

            ws = new WebSocket('ws://' + window.location.host + '/webrtc/signal');

            ws.onopen = async () => {
                log('WebSocket connected');
                await setupWebRTC(vxmlUrl);
            };

            ws.onmessage = async (event) => {
                const msg = JSON.parse(event.data);
                log('← ' + msg.type);

                if (msg.type === 'answer') {
                    await pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp });
                    setStatus('Call active', '#ccffcc');
                } else if (msg.type === 'ice') {
                    await pc.addIceCandidate(JSON.parse(msg.candidate));
                } else if (msg.type === 'audio') {
                    playAudioChunk(msg.data);
                } else if (msg.type === 'video') {
                    showVideo(msg.data, msg.mimeType);
                } else if (msg.type === 'clearVideo') {
                    clearVideo();
                } else if (msg.type === 'close') {
                    hangup();
                } else if (msg.type === 'error') {
                    log('Error: ' + msg.message);
                    setStatus('Error', '#ffcccc');
                }
            };

            ws.onerror = () => {
                log('WebSocket error');
                setStatus('Connection error', '#ffcccc');
            };

            ws.onclose = () => {
                log('WebSocket closed');
                cleanup();
            };
        }

        async function setupWebRTC(vxmlUrl) {
            try {
                localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
                log('Got microphone access');

                pc = new RTCPeerConnection(config);
                localStream.getTracks().forEach(track => pc.addTrack(track, localStream));

                dataChannel = pc.createDataChannel('vxml');
                dataChannel.onmessage = (e) => log('Data: ' + e.data);

                pc.ontrack = (event) => {
                    log('Got remote audio track');
                    const audio = new Audio();
                    audio.srcObject = event.streams[0];
                    audio.play();
                };

                pc.onicecandidate = (event) => {
                    if (event.candidate) {
                        ws.send(JSON.stringify({ type: 'ice', candidate: JSON.stringify(event.candidate) }));
                    }
                };

                pc.oniceconnectionstatechange = () => {
                    log('ICE: ' + pc.iceConnectionState);
                };

                const offer = await pc.createOffer();
                await pc.setLocalDescription(offer);
                ws.send(JSON.stringify({ type: 'offer', sdp: offer.sdp, vxmlUrl: vxmlUrl }));
                log('→ Offer sent');

            } catch (err) {
                log('Error: ' + err);
                setStatus('Setup failed', '#ffcccc');
                cleanup();
            }
        }

        function showVideo(base64Data, mimeType) {
            const videoContainer = document.getElementById('videoContainer');
            const videoDisplay = document.getElementById('videoDisplay');

            if (mimeType.startsWith('video/')) {
                const videoEl = document.createElement('video');
                videoEl.controls = true;
                videoEl.autoplay = true;
                videoEl.style.maxWidth = '100%';
                videoEl.src = 'data:' + mimeType + ';base64,' + base64Data;
                videoContainer.innerHTML = '';
                videoContainer.appendChild(videoEl);
            } else {
                videoDisplay.src = 'data:' + mimeType + ';base64,' + base64Data;
            }
            videoContainer.style.display = 'block';
        }

        function clearVideo() {
            document.getElementById('videoContainer').style.display = 'none';
        }

        function sendDtmf(digit) {
            if (dataChannel && dataChannel.readyState === 'open') {
                dataChannel.send(JSON.stringify({ type: 'dtmf', digit: digit }));
                log('→ DTMF ' + digit);
            } else if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ type: 'dtmf', digit: digit }));
                log('→ DTMF ' + digit);
            }
        }

        function hangup() {
            log('Hanging up');
            cleanup();
            if (ws) ws.close();
        }

        function cleanup() {
            if (dataChannel) { dataChannel.close(); dataChannel = null; }
            if (pc) { pc.close(); pc = null; }
            if (localStream) { localStream.getTracks().forEach(t => t.stop()); localStream = null; }
            if (audioContext) { audioContext.close(); audioContext = null; }
            audioQueue = [];
            isPlaying = false;
            document.getElementById('callBtn').disabled = false;
            document.getElementById('hangupBtn').disabled = true;
            document.querySelectorAll('.dtmf').forEach(btn => btn.disabled = true);
            setStatus('Ready', '#ddd');
        }

        async function playAudioChunk(base64Data) {
            if (!audioContext) {
                audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 8000 });
            }

            // Decode base64 to PCM
            const binaryString = atob(base64Data);
            const len = binaryString.length;
            const bytes = new Uint8Array(len);
            for (let i = 0; i < len; i++) {
                bytes[i] = binaryString.charCodeAt(i);
            }

            // Convert PCM bytes to Float32 samples (assuming 16-bit PCM)
            const samples = new Int16Array(bytes.buffer);
            const floatSamples = new Float32Array(samples.length);
            for (let i = 0; i < samples.length; i++) {
                floatSamples[i] = samples[i] / 32768.0;
            }

            // Create audio buffer
            const audioBuffer = audioContext.createBuffer(1, floatSamples.length, 8000);
            audioBuffer.getChannelData(0).set(floatSamples);

            audioQueue.push(audioBuffer);
            if (!isPlaying) {
                playNextChunk();
            }
        }

        function playNextChunk() {
            if (audioQueue.length === 0) {
                isPlaying = false;
                return;
            }

            isPlaying = true;
            const audioBuffer = audioQueue.shift();
            const source = audioContext.createBufferSource();
            source.buffer = audioBuffer;
            source.connect(audioContext.destination);
            source.onended = () => playNextChunk();
            source.start();
        }
    </script>
</body>
</html>
""";
    }
}
