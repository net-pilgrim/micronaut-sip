package net.pilgrim.msml;

import net.pilgrim.msml.builder.MsmlBuilder;
import net.pilgrim.msml.engine.DefaultMsmlMediaEngine;
import net.pilgrim.msml.engine.MsmlAudioLoader;
import net.pilgrim.msml.engine.MsmlConferenceManager;
import net.pilgrim.msml.model.AudioMixConfig;
import net.pilgrim.msml.model.MsmlCommand;
import net.pilgrim.msml.model.MsmlDocument;
import net.pilgrim.msml.model.StreamDirection;
import net.pilgrim.msml.model.moml.DtmfPattern;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;
import net.pilgrim.sip.rtp.config.RtpConfiguration;
import net.pilgrim.sip.rtp.media.AudioFrame;
import net.pilgrim.sip.rtp.media.RtpMediaManager;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.vxml.storage.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MsmlMediaEngineTest {

    private RtpMediaManager rtpManager;
    private MsmlConferenceManager conferenceManager;
    private MsmlAudioLoader audioLoader;
    private DefaultMsmlMediaEngine engine;

    @BeforeEach
    void setUp() {
        RtpConfiguration config = new RtpConfiguration();
        config.setPortRangeStart(31000);
        config.setPortRangeEnd(32000);
        config.setBindAddress("127.0.0.1");
        config.setWorkerThreads(2);
        rtpManager = new RtpMediaManager(config);
        ProvStorageConfiguration provConfig = new ProvStorageConfiguration();
        provConfig.setAllowFileProtocol(true);
        ProvStorageService storageService = new ProvStorageService(null, provConfig);
        MediaProtocolRegistry registry = new MediaProtocolRegistry();
        registry.register(new FileProtocolHandler(storageService));
        audioLoader = new MsmlAudioLoader(registry);
        conferenceManager = new MsmlConferenceManager();
        engine = new DefaultMsmlMediaEngine(rtpManager, conferenceManager, audioLoader);
    }

    @AfterEach
    void tearDown() {
        if (rtpManager != null) {
            rtpManager.close();
        }
    }

    @Test
    void executePlayToneDialog() throws Exception {
        RtpMediaSession session = rtpManager.createSession("call-play-1");
        BlockingQueue<MsmlEvent> events = new LinkedBlockingQueue<>();
        engine.addEventListener(events::add);

        MsmlDocument doc = MsmlBuilder.dialogStart("conn:call-play-1")
                .id("play-dlg")
                .play("tone:440,100", true)
                .build();

        MsmlCommand.DialogStart ds = (MsmlCommand.DialogStart) doc.commands().getFirst();
        CompletableFuture<MsmlResult> future = engine.executeDialog(ds, session);

        MsmlResult result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("play-dlg", result.id());

        MsmlEvent playComplete = events.poll(2, TimeUnit.SECONDS);
        assertNotNull(playComplete);
        assertEquals(MsmlEvent.PLAY_COMPLETE, playComplete.name());
        assertEquals("play-dlg", playComplete.id());
    }

    @Test
    void executeRecordDialogWithTermChar() throws Exception {
        RtpMediaSession caller = rtpManager.createSession("call-rec-caller");
        File tempWav = File.createTempFile("msml_rec_", ".wav");
        tempWav.deleteOnExit();

        BlockingQueue<MsmlEvent> events = new LinkedBlockingQueue<>();
        engine.addEventListener(events::add);

        MsmlDocument doc = MsmlBuilder.dialogStart("conn:call-rec-caller")
                .id("rec-dlg")
                .record(tempWav.toURI(), Duration.ofSeconds(10), "#", false)
                .build();

        MsmlCommand.DialogStart ds = (MsmlCommand.DialogStart) doc.commands().getFirst();
        CompletableFuture<MsmlResult> future = engine.executeDialog(ds, caller);

        // Allow record to start
        Thread.sleep(100);
        assertTrue(caller.isRecording());

        // Simulate caller pressing '#' to stop recording
        caller.notifyDtmfListeners('#');

        MsmlResult result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("rec-dlg", result.id());
        assertEquals("#", result.data().get("rec_termchar"));

        MsmlEvent recEvt = events.poll(2, TimeUnit.SECONDS);
        assertNotNull(recEvt);
        assertEquals(MsmlEvent.RECORD_COMPLETE, recEvt.name());
        assertEquals("#", recEvt.params().get("termchar"));
    }

    @Test
    void executeDtmfCollectionDialog() throws Exception {
        RtpMediaSession session = rtpManager.createSession("call-dtmf-1");
        BlockingQueue<MsmlEvent> events = new LinkedBlockingQueue<>();
        engine.addEventListener(events::add);

        MsmlDocument doc = MsmlBuilder.dialogStart("conn:call-dtmf-1")
                .id("dtmf-dlg")
                .dtmf(DtmfPattern.ofRange(1, 3, "#"))
                .exit("dtmf_digits")
                .build();

        MsmlCommand.DialogStart ds = (MsmlCommand.DialogStart) doc.commands().getFirst();
        CompletableFuture<MsmlResult> future = engine.executeDialog(ds, session);

        Thread.sleep(50);
        session.notifyDtmfListeners('1');
        session.notifyDtmfListeners('2');
        session.notifyDtmfListeners('#');

        MsmlResult result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("12", result.data().get("dtmf_digits"));

        MsmlEvent dtmfEvt = events.poll(2, TimeUnit.SECONDS);
        assertNotNull(dtmfEvt);
        assertEquals(MsmlEvent.DTMF_MATCH, dtmfEvt.name());
        assertEquals("12", dtmfEvt.params().get("digits"));
    }

    @Test
    void executeStopDialogCancels() throws Exception {
        RtpMediaSession session = rtpManager.createSession("call-stop-1");

        MsmlDocument doc = MsmlBuilder.dialogStart("conn:call-stop-1")
                .id("long-dlg")
                .sleep(Duration.ofSeconds(10))
                .build();

        MsmlCommand.DialogStart ds = (MsmlCommand.DialogStart) doc.commands().getFirst();
        CompletableFuture<MsmlResult> future = engine.executeDialog(ds, session);

        Thread.sleep(50);
        engine.stopDialog("long-dlg");

        MsmlResult result = future.get(2, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(487, result.responseCode());
    }

    @Test
    void conferenceBridgeAudioMixingAndNomedia() throws Exception {
        BlockingQueue<MsmlEvent> events = new LinkedBlockingQueue<>();
        engine.addEventListener(events::add);

        // 1. Create conference
        MsmlResult createRes = engine.createConference(new MsmlCommand.CreateConference(
                net.pilgrim.msml.model.MsmlTarget.conference("conf-test-1"), "nomedia", AudioMixConfig.standardPcm()));
        assertTrue(createRes.isSuccess());
        assertTrue(conferenceManager.hasConference("conf-test-1"));

        // 2. Join 2 participants
        RtpMediaSession p1 = rtpManager.createSession("call-p1");
        RtpMediaSession p2 = rtpManager.createSession("call-p2");
        p1.setRemoteAddress(new InetSocketAddress("127.0.0.1", p2.getLocalPort()));
        p2.setRemoteAddress(new InetSocketAddress("127.0.0.1", p1.getLocalPort()));

        MsmlResult j1 = engine.join(MsmlBuilder.join("conn:call-p1", "conf:conf-test-1").direction(StreamDirection.SENDRECV).buildCommand());
        assertTrue(j1.isSuccess());

        MsmlResult j2 = engine.join(MsmlBuilder.join("conn:call-p2", "conf:conf-test-1").direction(StreamDirection.SENDRECV).buildCommand());
        assertTrue(j2.isSuccess());

        assertEquals(2, conferenceManager.getConference("conf-test-1").orElseThrow().getParticipantCount());

        // 3. Test audio broadcast: p1 sends audio frame, conference forwards to p2
        List<AudioFrame> p2Received = new ArrayList<>();
        p2.addOutboundProcessor(p2Received::add);

        byte[] pcm = new byte[320];
        pcm[0] = 42;
        p1.onInboundPacket(new net.pilgrim.sip.rtp.transport.RtpInboundPacket(
                new net.pilgrim.sip.rtp.RtpPacket(false, p1.getCodec().payloadType(), 1, 0, 12345, p1.getCodec().encodePcm16Le(pcm)),
                new InetSocketAddress("127.0.0.1", 12345)));

        Thread.sleep(100);
        assertFalse(p2Received.isEmpty(), "Participant 2 should have received forwarded mixed audio");

        // 4. Unjoin both participants -> triggers nomedia event & bridge cleanup
        engine.unjoin((MsmlCommand.Unjoin) MsmlBuilder.unjoin("conn:call-p1", "conf:conf-test-1").commands().getFirst());
        engine.unjoin((MsmlCommand.Unjoin) MsmlBuilder.unjoin("conn:call-p2", "conf:conf-test-1").commands().getFirst());

        MsmlEvent nomedia = events.poll(2, TimeUnit.SECONDS);
        assertNotNull(nomedia);
        assertEquals(MsmlEvent.CONF_NOMEDIA, nomedia.name());
        assertEquals("conf-test-1", nomedia.id());
        assertFalse(conferenceManager.hasConference("conf-test-1"));
    }
}
