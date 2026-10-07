package net.pilgrim.sip.rtp.media;

import net.pilgrim.sip.rtp.config.RtpConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RtpMediaRecordingAndDtmfTest {

    private RtpMediaManager mediaManager;

    @BeforeEach
    void setUp() {
        RtpConfiguration config = new RtpConfiguration();
        config.setPortRangeStart(28000);
        config.setPortRangeEnd(29000);
        config.setBindAddress("127.0.0.1");
        config.setWorkerThreads(2);
        mediaManager = new RtpMediaManager(config);
    }

    @AfterEach
    void tearDown() {
        if (mediaManager != null) {
            mediaManager.close();
        }
    }

    @Test
    void testMediaSessionRecordingOverStream() throws Exception {
        RtpMediaSession sender = mediaManager.createSession("call-rec-sender");
        RtpMediaSession receiver = mediaManager.createSession("call-rec-receiver");

        sender.setRemoteAddress(new InetSocketAddress("127.0.0.1", receiver.getLocalPort()));
        receiver.setRemoteAddress(new InetSocketAddress("127.0.0.1", sender.getLocalPort()));

        assertFalse(receiver.isRecording());
        AudioRecorder recorder = receiver.startRecording(AudioRecorder.DirectionFilter.INBOUND_ONLY);
        assertTrue(receiver.isRecording());
        assertNotNull(recorder);
        assertSame(recorder, receiver.getActiveRecorder());

        // Send 5 audio frames (100 ms total, 5 * 320 bytes = 1600 bytes PCM)
        for (int i = 0; i < 5; i++) {
            byte[] pcm = new byte[320];
            for (int j = 0; j < pcm.length; j++) {
                pcm[j] = (byte) (j & 0x7F);
            }
            sender.sendAudioFrame(pcm, i == 0).block(Duration.ofSeconds(1));
            Thread.sleep(10);
        }

        Thread.sleep(100);

        AudioRecording recording = receiver.stopRecording();
        assertFalse(receiver.isRecording());
        assertNotNull(recording);
        assertEquals("call-rec-receiver", recording.getCallId());
        assertEquals(8000, recording.getSampleRate());
        assertEquals(1, recording.getChannels());
        assertTrue(recording.getPcmData().length >= 1600,
                "Should have captured at least 1600 bytes PCM, got: " + recording.getPcmData().length);

        byte[] wav = recording.toWavBytes();
        assertEquals(recording.getPcmData().length + 44, wav.length);
        assertEquals('R', (char) wav[0]);
        assertEquals('I', (char) wav[1]);
        assertEquals('F', (char) wav[2]);
        assertEquals('F', (char) wav[3]);
    }

    @Test
    void testRfc4733DtmfOverRtpStream() throws Exception {
        RtpMediaSession sender = mediaManager.createSession("call-dtmf-rfc-s");
        RtpMediaSession receiver = mediaManager.createSession("call-dtmf-rfc-r");

        sender.setRemoteAddress(new InetSocketAddress("127.0.0.1", receiver.getLocalPort()));
        receiver.setRemoteAddress(new InetSocketAddress("127.0.0.1", sender.getLocalPort()));

        BlockingQueue<Character> queue = new LinkedBlockingQueue<>();
        receiver.addDtmfListener(queue::offer);

        // Sender transmits telephone-event packets for digit '3'
        sender.sendTelephoneEvent('3', 100).block(Duration.ofSeconds(2));

        Character digit = queue.poll(2, TimeUnit.SECONDS);
        assertNotNull(digit, "Receiver should receive RFC 4733 DTMF digit");
        assertEquals('3', digit);

        // Verify debouncing: no second event should be queued
        Character extra = queue.poll(100, TimeUnit.MILLISECONDS);
        assertNull(extra, "RFC 4733 event must be debounced to single trigger");
    }

    @Test
    void testInbandDtmfOverRtpStream() throws Exception {
        RtpMediaSession sender = mediaManager.createSession("call-dtmf-inband-s");
        RtpMediaSession receiver = mediaManager.createSession("call-dtmf-inband-r");

        sender.setRemoteAddress(new InetSocketAddress("127.0.0.1", receiver.getLocalPort()));
        receiver.setRemoteAddress(new InetSocketAddress("127.0.0.1", sender.getLocalPort()));

        BlockingQueue<Character> queue = new LinkedBlockingQueue<>();
        receiver.addDtmfListener(queue::offer);
        assertTrue(receiver.isInbandDtmfEnabled());

        // Generate 80 ms inband dual tone for '9' (4 frames of 20ms)
        byte[] pcm = DtmfToneGenerator.generateTone('9', 80, 8000);

        for (int i = 0; i < 4; i++) {
            byte[] frame = new byte[320];
            System.arraycopy(pcm, i * 320, frame, 0, 320);
            sender.sendAudioFrame(frame, i == 0).block(Duration.ofSeconds(1));
            Thread.sleep(10);
        }

        Character digit = queue.poll(2, TimeUnit.SECONDS);
        assertNotNull(digit, "Receiver Goertzel detector should detect inband DTMF digit '9'");
        assertEquals('9', digit);
    }
}
