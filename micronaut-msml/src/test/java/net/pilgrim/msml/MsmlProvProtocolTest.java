package net.pilgrim.msml;

import net.pilgrim.msml.builder.MsmlBuilder;
import net.pilgrim.msml.builder.MsmlSerializer;
import net.pilgrim.msml.client.MsmlClient;
import net.pilgrim.msml.engine.DefaultMsmlMediaEngine;
import net.pilgrim.msml.engine.MsmlAudioLoader;
import net.pilgrim.msml.engine.MsmlConferenceManager;
import net.pilgrim.msml.model.MsmlCommand;
import net.pilgrim.msml.model.MsmlDocument;
import net.pilgrim.msml.model.moml.AudioSource;
import net.pilgrim.msml.model.moml.MomlElement;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.rtp.config.RtpConfiguration;
import net.pilgrim.sip.rtp.media.RtpMediaManager;
import net.pilgrim.sip.rtp.media.RtpMediaSession;
import net.pilgrim.vxml.speech.ToneGenerator;
import net.pilgrim.vxml.storage.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MsmlProvProtocolTest {

    @TempDir
    Path tempStorageDir;

    private RtpMediaManager rtpManager;
    private ProvStorageService provStorage;
    private MediaProtocolRegistry protocolRegistry;
    private MsmlAudioLoader audioLoader;
    private DefaultMsmlMediaEngine engine;

    @BeforeEach
    void setUp() {
        RtpConfiguration config = new RtpConfiguration();
        config.setPortRangeStart(35000);
        config.setPortRangeEnd(36000);
        config.setBindAddress("127.0.0.1");
        config.setWorkerThreads(2);
        rtpManager = new RtpMediaManager(config);

        ProvStorageConfiguration provConfig = new ProvStorageConfiguration();
        provConfig.setSafeRootPath(tempStorageDir.toString());
        provConfig.setSafeRootPrefix("media/");
        provConfig.setAllowFileProtocol(false); // file:// strictly forbidden to prevent exfiltration

        provStorage = new ProvStorageService(null, provConfig);
        protocolRegistry = new MediaProtocolRegistry(java.util.List.of(
                new ProvProtocolHandler(provStorage),
                new FileProtocolHandler(provStorage),
                new ToneProtocolHandler()
        ));
        audioLoader = new MsmlAudioLoader(protocolRegistry);
        engine = new DefaultMsmlMediaEngine(rtpManager, new MsmlConferenceManager(), audioLoader);
    }

    @AfterEach
    void tearDown() {
        if (rtpManager != null) {
            rtpManager.close();
        }
    }

    @Test
    void testStandardUriUsageWithProvProtocol() {
        // Uniform URI usage without leaking protocol-specific methods
        AudioSource src = AudioSource.of("prov://prompts/welcome.wav");
        assertTrue(src.isProv());
        assertEquals(URI.create("prov://prompts/welcome.wav"), src.uri());

        MsmlDocument doc = MsmlBuilder.dialogStart("conn:call-1")
                .id("prov-dlg")
                .play("prov://prompts/greeting.wav")
                .record(URI.create("prov://recordings/msg-1.wav"), Duration.ofSeconds(15), "#")
                .build();

        String xml = MsmlSerializer.serialize(doc);
        assertTrue(xml.contains("<audio uri=\"prov://prompts/greeting.wav\""));
        assertTrue(xml.contains("dest=\"prov://recordings/msg-1.wav\""));

        MsmlCommand.DialogStart ds = (MsmlCommand.DialogStart) doc.commands().getFirst();
        MomlElement.Play play = (MomlElement.Play) ds.momlDialog().elements().get(0);
        assertTrue(play.sources().getFirst().isProv());

        MomlElement.Record rec = (MomlElement.Record) ds.momlDialog().elements().get(1);
        assertEquals(URI.create("prov://recordings/msg-1.wav"), rec.dest());
    }

    @Test
    void testProvIsPluggableInJavaUriAndUrl() throws Exception {
        byte[] expectedTone = ToneGenerator.generateTone(440, 100);
        provStorage.storeAudio("prov://prompts/pluggable.wav", expectedTone);

        // 1. Transparent Java URLStreamHandlerProvider integration via URI.toURL()
        URI uri = URI.create("prov://prompts/pluggable.wav");
        URL url = uri.toURL();
        assertNotNull(url);
        assertEquals("prov", url.getProtocol());

        try (InputStream in = url.openStream()) {
            byte[] read = in.readAllBytes();
            assertArrayEquals(expectedTone, read, "Java URL.openStream() must read prov:// asset");
        }

        // 2. Encapsulated MediaUri wrapper
        MediaUri mediaUri = MediaUri.of("prov://prompts/pluggable.wav");
        assertTrue(mediaUri.isProv());
        try (InputStream in = mediaUri.openStream()) {
            byte[] read = in.readAllBytes();
            assertArrayEquals(expectedTone, read, "MediaUri.openStream() must read prov:// asset");
        }
    }

    @Test
    void testPlayProvAssetFromSafeRoot() throws Exception {
        RtpMediaSession session = rtpManager.createSession("call-play-prov");

        // Pre-provision audio asset in safe storage
        byte[] toneWav = ToneGenerator.generateTone(440, 100);
        provStorage.storeAudio("prov://prompts/greeting.wav", toneWav);

        MsmlDocument doc = MsmlBuilder.dialogStart("conn:call-play-prov")
                .id("play-prov-1")
                .play("prov://prompts/greeting.wav")
                .build();

        MsmlCommand.DialogStart ds = (MsmlCommand.DialogStart) doc.commands().getFirst();
        CompletableFuture<MsmlResult> future = engine.executeDialog(ds, session);

        MsmlResult result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("play-prov-1", result.id());
    }

    @Test
    void testFileProtocolBlockedToMitigateExfiltration() {
        // Attempting to load arbitrary host files via file:// is blocked
        byte[] loaded = audioLoader.loadAudio(URI.create("file:///etc/passwd"));
        assertNotNull(loaded);
        assertEquals(0, loaded.length, "file:///etc/passwd must be blocked to prevent data exfiltration");

        byte[] loadedShadow = audioLoader.loadAudio(URI.create("file:///etc/shadow"));
        assertNotNull(loadedShadow);
        assertEquals(0, loadedShadow.length, "file:///etc/shadow must be blocked");
    }

    @Test
    void testRecordToProvStoresInSafeRoot() throws Exception {
        RtpMediaSession session = rtpManager.createSession("call-rec-prov");
        BlockingQueue<MsmlEvent> events = new LinkedBlockingQueue<>();
        engine.addEventListener(events::add);

        MsmlDocument doc = MsmlBuilder.dialogStart("conn:call-rec-prov")
                .id("rec-prov-1")
                .record(URI.create("prov://recordings/user-msg-99.wav"), Duration.ofSeconds(10), "#", false)
                .build();

        MsmlCommand.DialogStart ds = (MsmlCommand.DialogStart) doc.commands().getFirst();
        CompletableFuture<MsmlResult> future = engine.executeDialog(ds, session);

        Thread.sleep(100);
        assertTrue(session.isRecording());

        // Stop recording with '#'
        session.notifyDtmfListeners('#');

        MsmlResult result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result.isSuccess());

        MsmlEvent event = events.poll(2, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals(MsmlEvent.RECORD_COMPLETE, event.name());
        assertEquals("prov://recordings/user-msg-99.wav", event.params().get("dest"));

        // Verify the asset was persisted in prov storage
        byte[] retrieved = provStorage.loadAudio(URI.create("prov://recordings/user-msg-99.wav"));
        assertNotNull(retrieved);
        assertTrue(retrieved.length > 0, "Recorded audio should be retrievable from prov:// storage");
    }

    @Test
    void testMsmlClientPlayAndRecordWithStandardUri() {
        AtomicReference<SipRequest> sent = new AtomicReference<>();
        InetSocketAddress ms = new InetSocketAddress("127.0.0.1", 5060);

        ReactiveSipClient stub = new ReactiveSipClient(null, null, null) {
            @Override
            public Mono<SipResponse> send(SipRequest req, InetSocketAddress dest) {
                sent.set(req);
                SipResponse ok = req.createResponse(200, "OK");
                ok.getHeaders().setContentType(MsmlDocument.CONTENT_TYPE);
                ok.setBody(MsmlBuilder.resultOk().toXml());
                return Mono.just(ok);
            }
        };

        MsmlClient client = new MsmlClient(stub);

        // Standard playAudio accepting prov URI string or URI object
        StepVerifier.create(client.playAudio("call-test", ms, "prov://prompts/hello.wav"))
                .assertNext(res -> assertTrue(res.isSuccess()))
                .verifyComplete();

        assertTrue(sent.get().getBodyAsString().contains("uri=\"prov://prompts/hello.wav\""));

        // Standard playAndRecord accepting URI objects
        StepVerifier.create(client.playAndRecord("call-test", ms,
                        URI.create("prov://prompts/beep.wav"),
                        URI.create("prov://recs/out.wav"),
                        Duration.ofSeconds(30), "#"))
                .assertNext(res -> assertTrue(res.isSuccess()))
                .verifyComplete();

        assertTrue(sent.get().getBodyAsString().contains("uri=\"prov://prompts/beep.wav\""));
        assertTrue(sent.get().getBodyAsString().contains("dest=\"prov://recs/out.wav\""));
    }
}
