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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
}
