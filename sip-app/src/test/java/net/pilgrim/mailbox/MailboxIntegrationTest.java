package net.pilgrim.mailbox;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import net.pilgrim.mailbox.controller.MailboxController;
import net.pilgrim.mailbox.service.MailboxRecordingService;
import net.pilgrim.mailbox.service.MailboxSession;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.dtmf.DtmfSignal;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.rtp.codec.G711UlawCodec;
import net.pilgrim.sip.rtp.RtpPacket;
import net.pilgrim.sip.sdp.SdpMessage;
import net.pilgrim.sip.sdp.SdpParser;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import net.pilgrim.sip.transport.SipNettyServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = "test")
public class MailboxIntegrationTest {

    @Inject
    SipNettyServer server;

    @Inject
    SipSessionManager sessionManager;

    @Inject
    ReactiveSipClient client;

    @Inject
    MailboxController mailboxController;

    @Inject
    MailboxRecordingService recordingService;

    private final SdpParser sdpParser = new SdpParser();
    private final G711UlawCodec g711Codec = new G711UlawCodec();

    @BeforeEach
    void setUp() {
        if (recordingService != null) {
            for (String key : recordingService.listMessages()) {
                recordingService.deleteMessage(key);
            }
        }
    }

    @Test
    void testSuccessfulMailboxCallAndVoicemailStorageWithDtmfHash() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "mailbox-test-full-" + UUID.randomUUID();

        try (DatagramSocket callerRtpSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            callerRtpSocket.setSoTimeout(3000);
            int callerRtpPort = callerRtpSocket.getLocalPort();

            String sdpOffer = """
                    v=0
                    o=Tester 1000 1000 IN IP4 127.0.0.1
                    s=MailboxTest
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0 8
                    a=rtpmap:0 PCMU/8000
                    a=rtpmap:8 PCMA/8000
                    a=sendrecv
                    """.formatted(callerRtpPort);

            SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:mailbox@127.0.0.1:" + server.getPort())
                    .from("<sip:caller@127.0.0.1>;tag=caller-tag-mailbox")
                    .to("<sip:mailbox@127.0.0.1>")
                    .callId(callId)
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            // 1. Send INVITE to mailbox@<sip-ip>
            SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(ok, "Server must respond to INVITE");
            assertEquals(200, ok.getStatusCode(), "Server must respond 200 OK to mailbox call");
            assertNotNull(ok.getContact(), "Contact header must be present");
            assertTrue(ok.getContact().contains("mailbox"), "Contact header must contain mailbox");
            assertEquals("application/sdp", ok.getContentType());

            // Parse server RTP audio port from SDP answer
            SdpMessage sdpAnswer = sdpParser.parse(ok.getBodyAsString());
            assertNotNull(sdpAnswer.findFirstAudioMedia());
            int serverRtpPort = sdpAnswer.findFirstAudioMedia().getPort();
            assertTrue(serverRtpPort > 0, "Server audio port must be valid positive port");
            InetSocketAddress serverRtpAddress = new InetSocketAddress("127.0.0.1", serverRtpPort);

            // 2. Send ACK to confirm dialog and initiate VoiceXML execution
            client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

            // Wait for session confirmation
            Optional<SipSession> sessionOpt = sessionManager.findSession(callId);
            assertTrue(sessionOpt.isPresent());
            long confirmDeadline = System.currentTimeMillis() + 2000;
            while (sessionOpt.get().getState() != SipSession.State.CONFIRMED && System.currentTimeMillis() < confirmDeadline) {
                Thread.sleep(10);
            }
            assertEquals(SipSession.State.CONFIRMED, sessionOpt.get().getState());

            // 3. Receive prompt RTP packets from answering machine
            byte[] recvBuf = new byte[1024];
            DatagramPacket promptPacket = new DatagramPacket(recvBuf, recvBuf.length);
            callerRtpSocket.receive(promptPacket);
            assertTrue(promptPacket.getLength() > 12, "Received prompt packet should be valid RTP");

            // 4. Wait for prompt chime (400ms) and beep tone (500ms) to complete into recording phase
            long recordDeadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < recordDeadline) {
                Optional<MailboxSession> mbOpt = mailboxController.findSession(callId);
                if (mbOpt.isPresent() && mbOpt.get().isRecording()) {
                    break;
                }
                Thread.sleep(20);
            }

            // 5. Send simulated voice audio packets (25 frames of 20ms = 500ms, 8000 bytes PCM)
            int seq = 100;
            long timestamp = 160000;
            long ssrc = 0x12345678L;
            for (int i = 0; i < 25; i++) {
                byte[] pcmFrame = new byte[320]; // 160 samples of 16-bit PCM @ 8kHz
                for (int j = 0; j < pcmFrame.length; j++) {
                    pcmFrame[j] = (byte) ((i * 10 + j) & 0x7F);
                }
                byte[] pcmuPayload = g711Codec.encodePcm16Le(pcmFrame);
                RtpPacket rtp = new RtpPacket(i == 0, 0, seq++, timestamp, ssrc, pcmuPayload);
                timestamp += 160;

                byte[] packetBytes = rtp.toBytes();
                DatagramPacket sendPacket = new DatagramPacket(packetBytes, packetBytes.length, serverRtpAddress);
                callerRtpSocket.send(sendPacket);
                Thread.sleep(20);
            }

            // 6. Caller finishes voicemail by pressing '#' via SIP INFO (dtmfterm="true")
            SipRequest info = SipRequest.builder(SipMethod.INFO, "sip:mailbox@127.0.0.1:" + server.getPort())
                    .from(invite.getFrom())
                    .to(ok.getTo())
                    .callId(callId)
                    .build();
            info.setDtmf(new DtmfSignal('#', 160));

            SipResponse infoResponse = client.send(info, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(infoResponse);
            assertEquals(200, infoResponse.getStatusCode());
            assertEquals("#", infoResponse.getHeaders().get("X-Received-DTMF"));

            // 7. Verify voicemail message is saved in object storage
            long waitDeadline = System.currentTimeMillis() + 4000;
            String foundKey = null;
            while (System.currentTimeMillis() < waitDeadline) {
                Set<String> messages = recordingService.listMessages();
                for (String msgKey : messages) {
                    if (msgKey.startsWith("caller/")) {
                        foundKey = msgKey;
                        break;
                    }
                }
                if (foundKey != null) {
                    break;
                }
                Thread.sleep(100);
            }

            assertNotNull(foundKey, "Recorded voicemail must be persisted in Object Storage");
            assertTrue(foundKey.startsWith("caller/"), "Key must start with caller/");
            assertTrue(foundKey.endsWith(".wav"), "Key must end with .wav");
            String[] keyParts = foundKey.split("/");
            assertEquals(2, keyParts.length, "Key must be in format caller/tstamp.wav");
            assertEquals("caller", keyParts[0]);
            String tstampPart = keyParts[1].substring(0, keyParts[1].length() - 4);
            assertDoesNotThrow(() -> Long.parseLong(tstampPart), "tstamp must be numeric timestamp");

            // 8. Retrieve message and verify WAV format and audio content
            Optional<byte[]> wavBytesOpt = recordingService.retrieveMessage(foundKey);
            assertTrue(wavBytesOpt.isPresent(), "Retrieved message must be present");
            byte[] wavBytes = wavBytesOpt.get();
            assertTrue(wavBytes.length > 44, "WAV bytes must contain header and audio payload");

            // RIFF header validation
            assertEquals('R', (char) wavBytes[0]);
            assertEquals('I', (char) wavBytes[1]);
            assertEquals('F', (char) wavBytes[2]);
            assertEquals('F', (char) wavBytes[3]);
            assertEquals('W', (char) wavBytes[8]);
            assertEquals('A', (char) wavBytes[9]);
            assertEquals('V', (char) wavBytes[10]);
            assertEquals('E', (char) wavBytes[11]);
        }
    }

    @Test
    void testMailboxCallEarlyHangupPersistsAudio() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "mailbox-test-hangup-" + UUID.randomUUID();

        try (DatagramSocket callerRtpSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            callerRtpSocket.setSoTimeout(3000);
            int callerRtpPort = callerRtpSocket.getLocalPort();

            String sdpOffer = """
                    v=0
                    o=Tester 1000 1000 IN IP4 127.0.0.1
                    s=MailboxTest
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0 8
                    a=rtpmap:0 PCMU/8000
                    a=sendrecv
                    """.formatted(callerRtpPort);

            SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:mailbox@127.0.0.1:" + server.getPort())
                    .from("<sip:caller-hangup@127.0.0.1>;tag=caller-hangup-tag")
                    .to("<sip:mailbox@127.0.0.1>")
                    .callId(callId)
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(ok);
            assertEquals(200, ok.getStatusCode());

            SdpMessage sdpAnswer = sdpParser.parse(ok.getBodyAsString());
            int serverRtpPort = sdpAnswer.findFirstAudioMedia().getPort();
            InetSocketAddress serverRtpAddress = new InetSocketAddress("127.0.0.1", serverRtpPort);

            client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

            // Wait for prompt and beep to finish and recording to start
            long recordDeadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < recordDeadline) {
                Optional<MailboxSession> mbOpt = mailboxController.findSession(callId);
                if (mbOpt.isPresent() && mbOpt.get().isRecording()) {
                    break;
                }
                Thread.sleep(20);
            }

            // Stream audio frames
            int seq = 200;
            long timestamp = 320000;
            long ssrc = 0x87654321L;
            for (int i = 0; i < 20; i++) {
                byte[] pcmFrame = new byte[320];
                for (int j = 0; j < pcmFrame.length; j++) {
                    pcmFrame[j] = (byte) ((i * 5 + j) & 0x7F);
                }
                byte[] pcmuPayload = g711Codec.encodePcm16Le(pcmFrame);
                RtpPacket rtp = new RtpPacket(i == 0, 0, seq++, timestamp, ssrc, pcmuPayload);
                timestamp += 160;

                byte[] packetBytes = rtp.toBytes();
                DatagramPacket sendPacket = new DatagramPacket(packetBytes, packetBytes.length, serverRtpAddress);
                callerRtpSocket.send(sendPacket);
                Thread.sleep(20);
            }

            // Caller hangs up early with BYE instead of pressing '#'
            SipResponse byeResponse = client.sendBye(invite, ok, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(byeResponse);
            assertEquals(200, byeResponse.getStatusCode());

            // Verify message captured up to hangup is saved
            long waitDeadline = System.currentTimeMillis() + 3000;
            String foundKey = null;
            while (System.currentTimeMillis() < waitDeadline) {
                Set<String> messages = recordingService.listMessages();
                for (String msgKey : messages) {
                    if (msgKey.startsWith("caller-hangup/")) {
                        foundKey = msgKey;
                        break;
                    }
                }
                if (foundKey != null) {
                    break;
                }
                Thread.sleep(100);
            }

            assertNotNull(foundKey, "Audio recorded before caller hangup must be saved to Object Storage");
            assertTrue(foundKey.startsWith("caller-hangup/"), "Key must start with caller-hangup/");
            assertTrue(foundKey.endsWith(".wav"), "Key must end with .wav");
            String[] hangupParts = foundKey.split("/");
            assertEquals(2, hangupParts.length, "Key must be in format caller/tstamp.wav");
            assertEquals("caller-hangup", hangupParts[0]);
            String hangupTstamp = hangupParts[1].substring(0, hangupParts[1].length() - 4);
            assertDoesNotThrow(() -> Long.parseLong(hangupTstamp), "tstamp must be numeric timestamp");
            Optional<byte[]> wavBytesOpt = recordingService.retrieveMessage(foundKey);
            assertTrue(wavBytesOpt.isPresent());
            assertTrue(wavBytesOpt.get().length > 44);
        }
    }

    @Test
    void testMailboxCallCancelledWithStarDtmfDiscardsAudio() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "mailbox-test-cancel-" + UUID.randomUUID();

        try (DatagramSocket callerRtpSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            callerRtpSocket.setSoTimeout(3000);
            int callerRtpPort = callerRtpSocket.getLocalPort();

            String sdpOffer = """
                    v=0
                    o=Tester 1000 1000 IN IP4 127.0.0.1
                    s=MailboxTest
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0 8
                    a=rtpmap:0 PCMU/8000
                    a=rtpmap:8 PCMA/8000
                    a=sendrecv
                    """.formatted(callerRtpPort);

            SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:mailbox@127.0.0.1:" + server.getPort())
                    .from("<sip:caller-cancel@127.0.0.1>;tag=caller-cancel-tag")
                    .to("<sip:mailbox@127.0.0.1>")
                    .callId(callId)
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(ok);
            assertEquals(200, ok.getStatusCode());

            SdpMessage sdpAnswer = sdpParser.parse(ok.getBodyAsString());
            int serverRtpPort = sdpAnswer.findFirstAudioMedia().getPort();
            InetSocketAddress serverRtpAddress = new InetSocketAddress("127.0.0.1", serverRtpPort);

            client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

            // Wait for prompt and beep to finish and recording to start
            long recordDeadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < recordDeadline) {
                Optional<MailboxSession> mbOpt = mailboxController.findSession(callId);
                if (mbOpt.isPresent() && mbOpt.get().isRecording()) {
                    break;
                }
                Thread.sleep(20);
            }

            // Stream voice audio frames
            int seq = 250;
            long timestamp = 400000;
            long ssrc = 0x55443322L;
            for (int i = 0; i < 20; i++) {
                byte[] pcmFrame = new byte[320];
                for (int j = 0; j < pcmFrame.length; j++) {
                    pcmFrame[j] = (byte) ((i * 7 + j) & 0x7F);
                }
                byte[] pcmuPayload = g711Codec.encodePcm16Le(pcmFrame);
                RtpPacket rtp = new RtpPacket(i == 0, 0, seq++, timestamp, ssrc, pcmuPayload);
                timestamp += 160;

                byte[] packetBytes = rtp.toBytes();
                DatagramPacket sendPacket = new DatagramPacket(packetBytes, packetBytes.length, serverRtpAddress);
                callerRtpSocket.send(sendPacket);
                Thread.sleep(20);
            }

            // Caller cancels voicemail by pressing '*' via SIP INFO
            SipRequest info = SipRequest.builder(SipMethod.INFO, "sip:mailbox@127.0.0.1:" + server.getPort())
                    .from(invite.getFrom())
                    .to(ok.getTo())
                    .callId(callId)
                    .build();
            info.setDtmf(new DtmfSignal('*', 160));

            SipResponse infoResponse = client.send(info, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(infoResponse);
            assertEquals(200, infoResponse.getStatusCode());
            assertEquals("*", infoResponse.getHeaders().get("X-Received-DTMF"));

            // Wait for cancellation prompt playback and dialog completion
            long completeDeadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < completeDeadline) {
                Optional<MailboxSession> mbOpt = mailboxController.findSession(callId);
                if (mbOpt.isEmpty() || mbOpt.get().isCompleted()) {
                    break;
                }
                Thread.sleep(50);
            }

            // Verify that NO message was persisted in Object Storage for caller-cancel
            Thread.sleep(500);
            Set<String> messages = recordingService.listMessages();
            boolean foundCancelledMessage = messages.stream().anyMatch(key -> key.startsWith("caller-cancel/"));
            assertFalse(foundCancelledMessage, "Cancelled voicemail must NOT be saved in Object Storage");
        }
    }

    @Test
    void testAliePlusMailboxCallStoresUnderOwnerAndCaller() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "mailbox-alie-test-" + UUID.randomUUID();

        try (DatagramSocket callerRtpSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            callerRtpSocket.setSoTimeout(3000);
            int callerRtpPort = callerRtpSocket.getLocalPort();

            String sdpOffer = """
                    v=0
                    o=Tester 1000 1000 IN IP4 127.0.0.1
                    s=MailboxTest
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0 8
                    a=rtpmap:0 PCMU/8000
                    a=sendrecv
                    """.formatted(callerRtpPort);

            // Calling alie+mailbox@...
            SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:alie+mailbox@127.0.0.1:" + server.getPort())
                    .from("<sip:bob@127.0.0.1>;tag=bob-tag-123")
                    .to("<sip:alie+mailbox@127.0.0.1>")
                    .callId(callId)
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(ok);
            assertEquals(200, ok.getStatusCode());
            assertTrue(ok.getContact().contains("alie+mailbox"), "Contact should reflect alie+mailbox");

            SdpMessage sdpAnswer = sdpParser.parse(ok.getBodyAsString());
            int serverRtpPort = sdpAnswer.findFirstAudioMedia().getPort();
            InetSocketAddress serverRtpAddress = new InetSocketAddress("127.0.0.1", serverRtpPort);

            client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

            // Wait for prompt and beep to finish and recording to start
            long recordDeadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < recordDeadline) {
                Optional<MailboxSession> mbOpt = mailboxController.findSession(callId);
                if (mbOpt.isPresent() && mbOpt.get().isRecording()) {
                    break;
                }
                Thread.sleep(20);
            }

            // Stream audio frames from bob
            int seq = 300;
            long timestamp = 480000;
            long ssrc = 0x99887766L;
            for (int i = 0; i < 20; i++) {
                byte[] pcmFrame = new byte[320];
                for (int j = 0; j < pcmFrame.length; j++) {
                    pcmFrame[j] = (byte) ((i * 3 + j) & 0x7F);
                }
                byte[] pcmuPayload = g711Codec.encodePcm16Le(pcmFrame);
                RtpPacket rtp = new RtpPacket(i == 0, 0, seq++, timestamp, ssrc, pcmuPayload);
                timestamp += 160;

                byte[] packetBytes = rtp.toBytes();
                DatagramPacket sendPacket = new DatagramPacket(packetBytes, packetBytes.length, serverRtpAddress);
                callerRtpSocket.send(sendPacket);
                Thread.sleep(20);
            }

            // Bob finishes message with '#' DTMF via SIP INFO
            SipRequest info = SipRequest.builder(SipMethod.INFO, "sip:alie+mailbox@127.0.0.1:" + server.getPort())
                    .from(invite.getFrom())
                    .to(ok.getTo())
                    .callId(callId)
                    .build();
            info.setDtmf(new DtmfSignal('#', 160));

            SipResponse infoResponse = client.send(info, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(infoResponse);
            assertEquals(200, infoResponse.getStatusCode());

            // Verify stored key format: alie/bob/tstamp.wav
            long waitDeadline = System.currentTimeMillis() + 4000;
            String foundKey = null;
            while (System.currentTimeMillis() < waitDeadline) {
                Set<String> messages = recordingService.listMessages();
                for (String msgKey : messages) {
                    if (msgKey.startsWith("alie/bob/")) {
                        foundKey = msgKey;
                        break;
                    }
                }
                if (foundKey != null) {
                    break;
                }
                Thread.sleep(100);
            }

            assertNotNull(foundKey, "Voicemail for alie from bob must be persisted");
            assertTrue(foundKey.startsWith("alie/bob/"), "Key must be in format alie/bob/tstamp.wav");
            assertTrue(foundKey.endsWith(".wav"));
            String[] parts = foundKey.split("/");
            assertEquals(3, parts.length);
            assertEquals("alie", parts[0]);
            assertEquals("bob", parts[1]);
            String tstampPart = parts[2].substring(0, parts[2].length() - 4);
            assertDoesNotThrow(() -> Long.parseLong(tstampPart), "Timestamp must be numeric");

            Optional<byte[]> wavBytesOpt = recordingService.retrieveMessage(foundKey);
            assertTrue(wavBytesOpt.isPresent());
            assertTrue(wavBytesOpt.get().length > 44);
        }
    }

    @Test
    void testOwnerRecordingCustomGreetingAndCallerHearingGreeting() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String greetingCallId = "greeting-test-" + UUID.randomUUID();

        // 1. Owner alice calls alice+greeting to record custom greeting
        try (DatagramSocket ownerRtpSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            ownerRtpSocket.setSoTimeout(3000);
            int ownerRtpPort = ownerRtpSocket.getLocalPort();

            String sdpOffer = """
                    v=0
                    o=Alice 1000 1000 IN IP4 127.0.0.1
                    s=GreetingRecord
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0 8
                    a=rtpmap:0 PCMU/8000
                    a=rtpmap:8 PCMA/8000
                    a=sendrecv
                    """.formatted(ownerRtpPort);

            SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:alice+greeting@127.0.0.1:" + server.getPort())
                    .from("<sip:alice@127.0.0.1>;tag=alice-tag-greeting")
                    .to("<sip:alice+greeting@127.0.0.1>")
                    .callId(greetingCallId)
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(ok);
            assertEquals(200, ok.getStatusCode());

            SdpMessage sdpAnswer = sdpParser.parse(ok.getBodyAsString());
            int serverRtpPort = sdpAnswer.findFirstAudioMedia().getPort();
            InetSocketAddress serverRtpAddress = new InetSocketAddress("127.0.0.1", serverRtpPort);

            client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

            // Wait for greeting recording to start
            long recordDeadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < recordDeadline) {
                Optional<MailboxSession> mbOpt = mailboxController.findSession(greetingCallId);
                if (mbOpt.isPresent() && mbOpt.get().isRecording()) {
                    break;
                }
                Thread.sleep(20);
            }

            // Stream custom greeting audio
            int seq = 500;
            long timestamp = 800000;
            long ssrc = 0xAABBCCDDL;
            for (int i = 0; i < 20; i++) {
                byte[] pcmFrame = new byte[320];
                for (int j = 0; j < pcmFrame.length; j++) {
                    pcmFrame[j] = (byte) ((i * 5 + j) & 0x7F);
                }
                byte[] pcmuPayload = g711Codec.encodePcm16Le(pcmFrame);
                RtpPacket rtp = new RtpPacket(i == 0, 0, seq++, timestamp, ssrc, pcmuPayload);
                timestamp += 160;

                byte[] packetBytes = rtp.toBytes();
                DatagramPacket sendPacket = new DatagramPacket(packetBytes, packetBytes.length, serverRtpAddress);
                ownerRtpSocket.send(sendPacket);
                Thread.sleep(20);
            }

            // Alice finishes greeting by pressing '#' DTMF
            SipRequest info = SipRequest.builder(SipMethod.INFO, "sip:alice+greeting@127.0.0.1:" + server.getPort())
                    .from(invite.getFrom())
                    .to(ok.getTo())
                    .callId(greetingCallId)
                    .build();
            info.setDtmf(new DtmfSignal('#', 160));

            SipResponse infoResponse = client.send(info, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(infoResponse);
            assertEquals(200, infoResponse.getStatusCode());

            // Verify custom greeting is stored in object storage
            long waitDeadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < waitDeadline) {
                if (recordingService.hasCustomGreeting("alice")) {
                    break;
                }
                Thread.sleep(100);
            }

            assertTrue(recordingService.hasCustomGreeting("alice"), "Custom greeting for alice must be stored");
            Optional<byte[]> greetingAudio = recordingService.retrieveGreeting("alice");
            assertTrue(greetingAudio.isPresent());
            assertTrue(greetingAudio.get().length > 44);
        }

        // 2. Caller bob calls alice+mailbox to deposit a message
        String depositCallId = "deposit-test-" + UUID.randomUUID();
        try (DatagramSocket callerRtpSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            callerRtpSocket.setSoTimeout(3000);
            int callerRtpPort = callerRtpSocket.getLocalPort();

            String sdpOffer = """
                    v=0
                    o=Bob 2000 2000 IN IP4 127.0.0.1
                    s=CallerDeposit
                    c=IN IP4 127.0.0.1
                    t=0 0
                    m=audio %d RTP/AVP 0 8
                    a=rtpmap:0 PCMU/8000
                    a=rtpmap:8 PCMA/8000
                    a=sendrecv
                    """.formatted(callerRtpPort);

            SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:alice+mailbox@127.0.0.1:" + server.getPort())
                    .from("<sip:bob@127.0.0.1>;tag=bob-tag-deposit")
                    .to("<sip:alice+mailbox@127.0.0.1>")
                    .callId(depositCallId)
                    .contentType("application/sdp")
                    .body(sdpOffer)
                    .build();

            SipResponse ok = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(ok);
            assertEquals(200, ok.getStatusCode());

            SdpMessage sdpAnswer = sdpParser.parse(ok.getBodyAsString());
            int serverRtpPort = sdpAnswer.findFirstAudioMedia().getPort();
            InetSocketAddress serverRtpAddress = new InetSocketAddress("127.0.0.1", serverRtpPort);

            client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(2));

            // Wait for recording to begin
            long recordDeadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < recordDeadline) {
                Optional<MailboxSession> mbOpt = mailboxController.findSession(depositCallId);
                if (mbOpt.isPresent() && mbOpt.get().isRecording()) {
                    break;
                }
                Thread.sleep(20);
            }

            // Bob streams message audio
            int seq = 600;
            long timestamp = 900000;
            long ssrc = 0x11223344L;
            for (int i = 0; i < 20; i++) {
                byte[] pcmFrame = new byte[320];
                for (int j = 0; j < pcmFrame.length; j++) {
                    pcmFrame[j] = (byte) ((i * 4 + j) & 0x7F);
                }
                byte[] pcmuPayload = g711Codec.encodePcm16Le(pcmFrame);
                RtpPacket rtp = new RtpPacket(i == 0, 0, seq++, timestamp, ssrc, pcmuPayload);
                timestamp += 160;

                byte[] packetBytes = rtp.toBytes();
                DatagramPacket sendPacket = new DatagramPacket(packetBytes, packetBytes.length, serverRtpAddress);
                callerRtpSocket.send(sendPacket);
                Thread.sleep(20);
            }

            // Bob finishes with '#'
            SipRequest info = SipRequest.builder(SipMethod.INFO, "sip:alice+mailbox@127.0.0.1:" + server.getPort())
                    .from(invite.getFrom())
                    .to(ok.getTo())
                    .callId(depositCallId)
                    .build();
            info.setDtmf(new DtmfSignal('#', 160));

            SipResponse infoResponse = client.send(info, serverAddress).block(Duration.ofSeconds(3));
            assertNotNull(infoResponse);
            assertEquals(200, infoResponse.getStatusCode());

            // Verify message is saved for alice from bob
            long waitDeadline = System.currentTimeMillis() + 4000;
            String foundMsgKey = null;
            while (System.currentTimeMillis() < waitDeadline) {
                var msgs = recordingService.listMessagesForOwner("alice");
                if (!msgs.isEmpty()) {
                    foundMsgKey = msgs.get(0).key();
                    break;
                }
                Thread.sleep(100);
            }

            assertNotNull(foundMsgKey, "Message from bob for alice must be present");
            assertTrue(foundMsgKey.startsWith("alice/bob/"));
            assertEquals(1, recordingService.getOwnerMessageCount("alice"));

            // Alice cleans up custom greeting
            assertTrue(recordingService.deleteGreeting("alice"));
            assertFalse(recordingService.hasCustomGreeting("alice"));
        }
    }

    @Test
    void testTenConcurrentVoicemailCalls() throws Exception {
        int concurrentCount = 10;
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        ExecutorService executor = Executors.newFixedThreadPool(concurrentCount);
        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentCount; i++) {
            final int index = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                String callId = "concurrent-vm-" + index + "-" + UUID.randomUUID();
                String callerUser = "caller" + index;
                String ownerUser = "owner" + (index % 3);

                try (DatagramSocket callerRtpSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
                    callerRtpSocket.setSoTimeout(5000);
                    int callerRtpPort = callerRtpSocket.getLocalPort();

                    String sdpOffer = """
                            v=0
                            o=Caller 1000 1000 IN IP4 127.0.0.1
                            s=ConcurrentTest
                            c=IN IP4 127.0.0.1
                            t=0 0
                            m=audio %d RTP/AVP 0 8
                            a=rtpmap:0 PCMU/8000
                            a=rtpmap:8 PCMA/8000
                            a=sendrecv
                            """.formatted(callerRtpPort);

                    SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:" + ownerUser + "+mailbox@127.0.0.1:" + server.getPort())
                            .from("<sip:" + callerUser + "@127.0.0.1>;tag=tag-" + index)
                            .to("<sip:" + ownerUser + "+mailbox@127.0.0.1>")
                            .callId(callId)
                            .contentType("application/sdp")
                            .body(sdpOffer)
                            .build();

                    SipResponse ok = client.send(invite, serverAddress, Duration.ofSeconds(10)).block(Duration.ofSeconds(10));
                    assertNotNull(ok, "Response for call " + index + " must not be null");
                    assertEquals(200, ok.getStatusCode(), "Call " + index + " must succeed with 200 OK");

                    SdpMessage sdpAnswer = sdpParser.parse(ok.getBodyAsString());
                    int serverRtpPort = sdpAnswer.findFirstAudioMedia().getPort();
                    InetSocketAddress serverRtpAddress = new InetSocketAddress("127.0.0.1", serverRtpPort);

                    client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(5));

                    // Wait for recording to begin
                    long deadline = System.currentTimeMillis() + 8000;
                    while (System.currentTimeMillis() < deadline) {
                        Optional<MailboxSession> mbOpt = mailboxController.findSession(callId);
                        if (mbOpt.isPresent() && mbOpt.get().isRecording()) {
                            break;
                        }
                        Thread.sleep(30);
                    }

                    // Stream voice packets (20 frames of 20ms = 400ms)
                    int seq = 100 + index * 50;
                    long timestamp = 100000L + index * 10000L;
                    long ssrc = 0x55000000L + index;
                    for (int f = 0; f < 20; f++) {
                        byte[] pcmFrame = new byte[320];
                        for (int j = 0; j < pcmFrame.length; j++) {
                            pcmFrame[j] = (byte) ((f * 3 + j + index) & 0x7F);
                        }
                        byte[] pcmuPayload = g711Codec.encodePcm16Le(pcmFrame);
                        RtpPacket rtp = new RtpPacket(f == 0, 0, seq++, timestamp, ssrc, pcmuPayload);
                        timestamp += 160;

                        byte[] packetBytes = rtp.toBytes();
                        DatagramPacket sendPacket = new DatagramPacket(packetBytes, packetBytes.length, serverRtpAddress);
                        callerRtpSocket.send(sendPacket);
                        Thread.sleep(20);
                    }

                    // Finish message with '#' via SIP INFO
                    SipRequest info = SipRequest.builder(SipMethod.INFO, "sip:" + ownerUser + "+mailbox@127.0.0.1:" + server.getPort())
                            .from(invite.getFrom())
                            .to(ok.getTo())
                            .callId(callId)
                            .build();
                    info.setDtmf(new DtmfSignal('#', 160));

                    SipResponse infoResp = client.send(info, serverAddress, Duration.ofSeconds(10)).block(Duration.ofSeconds(10));
                    assertNotNull(infoResp);
                    assertEquals(200, infoResp.getStatusCode());

                    return callId;
                } catch (Exception e) {
                    throw new RuntimeException("Call " + index + " failed: " + e.getMessage(), e);
                }
            }, executor));
        }

        // Wait for all 10 calls to finish
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        // Verify that all 10 messages were stored
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            Set<String> allMsgs = recordingService.listMessages();
            long count = allMsgs.stream().filter(k -> k.endsWith(".wav") && !k.endsWith("greeting.wav")).count();
            if (count >= concurrentCount) {
                break;
            }
            Thread.sleep(100);
        }

        Set<String> messages = recordingService.listMessages();
        long savedVoicemails = messages.stream().filter(k -> k.endsWith(".wav") && !k.endsWith("greeting.wav")).count();
        assertEquals(concurrentCount, savedVoicemails, "All 10 concurrent voicemails must be successfully recorded and persisted");
    }

    @Test
    void testHundredConcurrentVoicemailCalls() throws Exception {
        int concurrentCount = 100;
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("concurrent-100-", 0).factory());
        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentCount; i++) {
            final int index = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                String callId = "c100-vm-" + index + "-" + UUID.randomUUID();
                String callerUser = "c100caller" + index;
                String ownerUser = "c100owner" + (index % 10);

                try (DatagramSocket callerRtpSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
                    callerRtpSocket.setSoTimeout(20000);
                    int callerRtpPort = callerRtpSocket.getLocalPort();

                    String sdpOffer = """
                            v=0
                            o=Caller 1000 1000 IN IP4 127.0.0.1
                            s=ConcurrentTest100
                            c=IN IP4 127.0.0.1
                            t=0 0
                            m=audio %d RTP/AVP 0 8
                            a=rtpmap:0 PCMU/8000
                            a=rtpmap:8 PCMA/8000
                            a=sendrecv
                            """.formatted(callerRtpPort);

                    SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:" + ownerUser + "+mailbox@127.0.0.1:" + server.getPort())
                            .from("<sip:" + callerUser + "@127.0.0.1>;tag=tag100-" + index)
                            .to("<sip:" + ownerUser + "+mailbox@127.0.0.1>")
                            .callId(callId)
                            .contentType("application/sdp")
                            .body(sdpOffer)
                            .build();

                    SipResponse ok = client.send(invite, serverAddress, Duration.ofSeconds(20)).block(Duration.ofSeconds(20));
                    assertNotNull(ok, "Response for call " + index + " must not be null");
                    assertEquals(200, ok.getStatusCode(), "Call " + index + " must succeed with 200 OK");

                    SdpMessage sdpAnswer = sdpParser.parse(ok.getBodyAsString());
                    int serverRtpPort = sdpAnswer.findFirstAudioMedia().getPort();
                    InetSocketAddress serverRtpAddress = new InetSocketAddress("127.0.0.1", serverRtpPort);

                    client.sendAck(invite, ok, serverAddress).block(Duration.ofSeconds(10));

                    // Wait for recording to begin
                    long deadline = System.currentTimeMillis() + 15000;
                    while (System.currentTimeMillis() < deadline) {
                        Optional<MailboxSession> mbOpt = mailboxController.findSession(callId);
                        if (mbOpt.isPresent() && mbOpt.get().isRecording()) {
                            break;
                        }
                        Thread.sleep(30);
                    }

                    // Stream voice packets (20 frames of 20ms = 400ms at 300 Hz pure voice tone)
                    int seq = 100 + index * 50;
                    long timestamp = 100000L + index * 10000L;
                    long ssrc = 0x66000000L + index;
                    for (int f = 0; f < 20; f++) {
                        byte[] pcmFrame = new byte[320];
                        for (int j = 0; j < 160; j++) {
                            double angle = 2.0 * Math.PI * 300.0 * (f * 160 + j) / 8000.0;
                            short sample = (short) (Math.sin(angle) * 8000);
                            pcmFrame[j * 2] = (byte) (sample & 0xFF);
                            pcmFrame[j * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
                        }
                        byte[] pcmuPayload = g711Codec.encodePcm16Le(pcmFrame);
                        RtpPacket rtp = new RtpPacket(f == 0, 0, seq++, timestamp, ssrc, pcmuPayload);
                        timestamp += 160;

                        byte[] packetBytes = rtp.toBytes();
                        DatagramPacket sendPacket = new DatagramPacket(packetBytes, packetBytes.length, serverRtpAddress);
                        callerRtpSocket.send(sendPacket);
                        Thread.sleep(20);
                    }

                    // Finish message with '#' via SIP INFO
                    SipRequest info = SipRequest.builder(SipMethod.INFO, "sip:" + ownerUser + "+mailbox@127.0.0.1:" + server.getPort())
                            .from(invite.getFrom())
                            .to(ok.getTo())
                            .callId(callId)
                            .build();
                    info.setDtmf(new DtmfSignal('#', 160));

                    SipResponse infoResp = client.send(info, serverAddress, Duration.ofSeconds(20)).block(Duration.ofSeconds(20));
                    assertNotNull(infoResp);
                    assertEquals(200, infoResp.getStatusCode());

                    return callId;
                } catch (Exception e) {
                    throw new RuntimeException("Call " + index + " failed: " + e.getMessage(), e);
                }
            }, executor));
        }

        // Wait for all 100 calls to finish
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        // Verify that all 100 messages were stored
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            Set<String> allMsgs = recordingService.listMessages();
            long count = allMsgs.stream().filter(k -> k.contains("c100caller") && k.endsWith(".wav")).count();
            if (count >= concurrentCount) {
                break;
            }
            Thread.sleep(100);
        }

        Set<String> messages = recordingService.listMessages();
        long savedVoicemails = messages.stream().filter(k -> k.contains("c100caller") && k.endsWith(".wav")).count();
        assertEquals(concurrentCount, savedVoicemails, "All 100 concurrent voicemails must be successfully recorded and persisted");
    }

    @Test
    void testNonMailboxCallDoesNotTriggerMailbox() {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.getPort());
        String callId = "non-mailbox-" + UUID.randomUUID();

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:alice@127.0.0.1:" + server.getPort())
                .from("<sip:caller@127.0.0.1>;tag=caller-alice")
                .to("<sip:alice@127.0.0.1>")
                .callId(callId)
                .build();

        SipResponse response = client.send(invite, serverAddress).block(Duration.ofSeconds(3));
        assertNotNull(response);
        // In unified sip-app, calls to alice are handled by CallController (200 OK), not MailboxController
        assertEquals(200, response.getStatusCode());
        assertTrue(mailboxController.findSession(callId).isEmpty(), "Non-mailbox call must not create a mailbox session");
    }
}
