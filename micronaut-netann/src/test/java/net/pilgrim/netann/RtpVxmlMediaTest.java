package net.pilgrim.netann;

import net.pilgrim.netann.vxml.media.RtpVxmlMedia;
import net.pilgrim.netann.vxml.media.RtpVxmlMediaFactory;
import net.pilgrim.sip.rtp.config.RtpConfiguration;
import net.pilgrim.sip.rtp.media.RtpMediaManager;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.vxml.ast.VxmlDocument;
import net.pilgrim.vxml.media.VxmlMedia;
import net.pilgrim.vxml.parser.VxmlParser;
import net.pilgrim.vxml.runtime.VxmlInterpreter;
import net.pilgrim.vxml.runtime.VxmlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.pilgrim.sip.rtp.media.AudioRecording;
import net.pilgrim.sip.rtp.media.DtmfToneGenerator;

import static org.junit.jupiter.api.Assertions.*;

class RtpVxmlMediaTest {

    private RtpMediaManager rtpMediaManager;
    private final VxmlParser parser = new VxmlParser();

    @BeforeEach
    void setUp() {
        RtpConfiguration config = new RtpConfiguration();
        config.setPortRangeStart(28500);
        config.setPortRangeEnd(29500);
        config.setBindAddress("127.0.0.1");
        config.setWorkerThreads(2);
        rtpMediaManager = new RtpMediaManager(config);
    }

    @AfterEach
    void tearDown() {
        if (rtpMediaManager != null) {
            rtpMediaManager.close();
        }
    }

    @Test
    void testRtpVxmlMediaStreamsAudioViaRtpSession() throws Exception {
        RtpMediaSession sender = rtpMediaManager.createSession("call-vxml-media-1");
        RtpMediaSession receiver = rtpMediaManager.createSession("call-vxml-media-rec-1");
        sender.setRemoteAddress(new InetSocketAddress("127.0.0.1", receiver.getLocalPort()));

        RtpVxmlMedia rtpMedia = new RtpVxmlMedia(sender);
        assertSame(sender, rtpMedia.getMediaSession());

        byte[] audioData = new byte[320 * 2]; // 2 frames (40ms)
        CountDownLatch latch = new CountDownLatch(1);

        assertFalse(rtpMedia.isAudioPlaying());
        rtpMedia.playAudio(audioData, true, latch::countDown);

        assertTrue(rtpMedia.isBargeInAllowed());
        boolean finished = latch.await(2, TimeUnit.SECONDS);
        assertTrue(finished, "RTP audio playback should complete callback");
        assertFalse(rtpMedia.isAudioPlaying());
    }

    @Test
    void testInjectRtpVxmlMediaIntoInterpreter() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <var name="answered" expr="'no'"/>
                  <form id="greeting">
                    <block>
                      <prompt>Welcome to voice service</prompt>
                      <assign name="answered" expr="'yes'"/>
                      <exit/>
                    </block>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        RtpMediaSession rtpSession = rtpMediaManager.createSession("call-interpreter-rtp");
        RtpVxmlMedia rtpMedia = new RtpVxmlMedia(rtpSession);

        AtomicBoolean dialogCompleted = new AtomicBoolean(false);

        // Inject RTP media object into the VoiceXML interpreter
        VxmlInterpreter interpreter = VxmlSession.builder()
                .callId("call-interpreter-rtp")
                .document(doc)
                .documentUri("inline:greeting")
                .media(rtpMedia)
                .onDialogComplete(() -> dialogCompleted.set(true))
                .build();

        assertSame(rtpMedia, interpreter.getMedia());

        interpreter.start();

        assertTrue(dialogCompleted.get());
        assertEquals("yes", interpreter.getDocumentScope().get("answered"));
        assertEquals(VxmlSession.State.TERMINATED, interpreter.getState());
    }

    @Test
    void testRtpVxmlMediaFactoryProduction() throws Exception {
        RtpVxmlMediaFactory factory = new RtpVxmlMediaFactory();
        RtpMediaSession session = rtpMediaManager.createSession("call-factory-test");

        VxmlMedia media = factory.createMedia("call-factory-test", session);
        assertNotNull(media);
        assertInstanceOf(RtpVxmlMedia.class, media);
        assertSame(session, ((RtpVxmlMedia) media).getMediaSession());
    }

    @Test
    void testRtpVxmlMediaRecording() throws Exception {
        RtpMediaSession sender = rtpMediaManager.createSession("call-rec-s");
        RtpMediaSession receiver = rtpMediaManager.createSession("call-rec-r");
        sender.setRemoteAddress(new InetSocketAddress("127.0.0.1", receiver.getLocalPort()));
        receiver.setRemoteAddress(new InetSocketAddress("127.0.0.1", sender.getLocalPort()));

        RtpVxmlMedia rtpMedia = new RtpVxmlMedia(receiver);

        assertFalse(rtpMedia.isRecording());
        rtpMedia.startRecording();
        assertTrue(rtpMedia.isRecording());

        // Send 4 audio frames from sender (80ms = 1280 bytes)
        for (int i = 0; i < 4; i++) {
            byte[] frame = new byte[320];
            frame[0] = (byte) (i + 1);
            sender.sendAudioFrame(frame, i == 0).block(Duration.ofSeconds(1));
            Thread.sleep(10);
        }

        Thread.sleep(80);

        assertTrue(rtpMedia.isRecording());
        AudioRecording recording = rtpMedia.stopAudioRecording();
        assertFalse(rtpMedia.isRecording());

        assertNotNull(recording);
        assertTrue(recording.getPcmData().length >= 1280,
                "Should have recorded at least 1280 bytes, got " + recording.getPcmData().length);
        byte[] wav = recording.toWavBytes();
        assertEquals(recording.getPcmData().length + 44, wav.length);
    }

    @Test
    void testRtpVxmlMediaDtmfStreamingIntoInterpreter() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <var name="dest" expr="'none'"/>
                  <menu id="ivr">
                    <prompt>Press 1 for Sales or 2 for Support</prompt>
                    <choice dtmf="1" next="#sales"/>
                    <choice dtmf="2" next="#support"/>
                  </menu>
                  <form id="sales">
                    <block>
                      <assign name="dest" expr="'sales'"/>
                      <exit/>
                    </block>
                  </form>
                  <form id="support">
                    <block>
                      <assign name="dest" expr="'support'"/>
                      <exit/>
                    </block>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        RtpMediaSession sender = rtpMediaManager.createSession("call-ivr-caller");
        RtpMediaSession receiver = rtpMediaManager.createSession("call-ivr-server");
        sender.setRemoteAddress(new InetSocketAddress("127.0.0.1", receiver.getLocalPort()));
        receiver.setRemoteAddress(new InetSocketAddress("127.0.0.1", sender.getLocalPort()));

        RtpVxmlMedia rtpMedia = new RtpVxmlMedia(receiver);
        AtomicBoolean dialogCompleted = new AtomicBoolean(false);

        VxmlInterpreter interpreter = VxmlSession.builder()
                .callId("call-ivr-server")
                .document(doc)
                .documentUri("inline:ivr")
                .media(rtpMedia)
                .onDialogComplete(() -> dialogCompleted.set(true))
                .build();

        interpreter.start();

        // While waiting for user input, state is WAITING_FOR_INPUT
        assertEquals(VxmlSession.State.WAITING_FOR_INPUT, interpreter.getState());
        assertFalse(dialogCompleted.get());

        // Caller sends DTMF digit '1' via RFC 4733 over RTP
        sender.sendTelephoneEvent('1', 100).block(Duration.ofSeconds(2));

        // Wait for interpreter to process DTMF input and transition
        int retries = 0;
        while (!dialogCompleted.get() && retries < 20) {
            Thread.sleep(100);
            retries++;
        }

        assertTrue(dialogCompleted.get(), "Interpreter should complete after receiving DTMF '1'");
        assertEquals("sales", interpreter.getDocumentScope().get("dest"));
        assertEquals(VxmlSession.State.TERMINATED, interpreter.getState());
    }
}
