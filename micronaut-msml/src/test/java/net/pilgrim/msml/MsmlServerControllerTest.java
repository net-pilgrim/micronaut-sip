package net.pilgrim.msml;

import net.pilgrim.msml.builder.MsmlBuilder;
import net.pilgrim.msml.builder.MsmlSerializer;
import net.pilgrim.msml.engine.DefaultMsmlMediaEngine;
import net.pilgrim.msml.engine.MsmlAudioLoader;
import net.pilgrim.msml.engine.MsmlConferenceManager;
import net.pilgrim.msml.model.MsmlDocument;
import net.pilgrim.msml.parser.MsmlParser;
import net.pilgrim.msml.server.MsmlServerController;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.rtp.config.RtpConfiguration;
import net.pilgrim.sip.rtp.media.RtpMediaManager;
import net.pilgrim.sip.sdp.SdpNegotiator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class MsmlServerControllerTest {

    private RtpMediaManager rtpManager;
    private MsmlConferenceManager conferenceManager;
    private DefaultMsmlMediaEngine engine;
    private MsmlParser parser;
    private MsmlServerController controller;

    @BeforeEach
    void setUp() {
        RtpConfiguration config = new RtpConfiguration();
        config.setPortRangeStart(33000);
        config.setPortRangeEnd(34000);
        config.setBindAddress("127.0.0.1");
        config.setWorkerThreads(2);
        rtpManager = new RtpMediaManager(config);
        conferenceManager = new MsmlConferenceManager();
        engine = new DefaultMsmlMediaEngine(rtpManager, conferenceManager, new MsmlAudioLoader());
        parser = new MsmlParser();
        controller = new MsmlServerController(engine, parser, rtpManager, new SdpNegotiator(), null);
    }

    @AfterEach
    void tearDown() {
        if (rtpManager != null) {
            rtpManager.close();
        }
    }

    @Test
    void testOnInfoCreateConference() {
        MsmlDocument doc = MsmlBuilder.createConference("conf:room-42")
                .sampleRate("8000")
                .build();
        String xml = MsmlSerializer.serialize(doc);

        SipRequest request = SipRequest.builder(SipMethod.INFO, "sip:msml@127.0.0.1:5060")
                .callId("ctrl-call-1")
                .contentType(MsmlDocument.CONTENT_TYPE)
                .body(xml)
                .build();

        StepVerifier.create(controller.onInfo(request, "ctrl-call-1", xml, MsmlDocument.CONTENT_TYPE))
                .assertNext(response -> {
                    assertEquals(200, response.getStatusCode());
                    assertEquals(MsmlDocument.CONTENT_TYPE, response.getContentType());
                    assertNotNull(response.getBodyAsString());

                    MsmlDocument respDoc = parser.parse(response.getBodyAsString());
                    assertTrue(respDoc.isResult());
                    assertEquals(1, respDoc.results().size());
                    assertEquals(200, respDoc.results().getFirst().responseCode());
                })
                .verifyComplete();

        assertTrue(conferenceManager.hasConference("room-42"));
    }

    @Test
    void testOnInfoDestroyConference() {
        // Pre-create conference
        engine.createConference(MsmlBuilder.createConference("conf:room-99").buildCommand());
        assertTrue(conferenceManager.hasConference("room-99"));

        MsmlDocument destroyDoc = MsmlBuilder.destroyConference("conf:room-99");
        String xml = MsmlSerializer.serialize(destroyDoc);

        SipRequest request = SipRequest.builder(SipMethod.INFO, "sip:msml@127.0.0.1:5060")
                .callId("ctrl-call-2")
                .contentType(MsmlDocument.CONTENT_TYPE)
                .body(xml)
                .build();

        StepVerifier.create(controller.onInfo(request, "ctrl-call-2", xml, MsmlDocument.CONTENT_TYPE))
                .assertNext(response -> {
                    assertEquals(200, response.getStatusCode());
                    MsmlDocument respDoc = parser.parse(response.getBodyAsString());
                    assertTrue(respDoc.isResult());
                    assertEquals(200, respDoc.results().getFirst().responseCode());
                })
                .verifyComplete();

        assertFalse(conferenceManager.hasConference("room-99"));
    }

    @Test
    void testOnInfoInvalidXmlReturnsErrorResult() {
        SipRequest request = SipRequest.builder(SipMethod.INFO, "sip:msml@127.0.0.1:5060")
                .callId("bad-call")
                .contentType(MsmlDocument.CONTENT_TYPE)
                .body("<msml version=\"1.1\"><unclosed>")
                .build();

        StepVerifier.create(controller.onInfo(request, "bad-call", "<msml version=\"1.1\"><unclosed>", MsmlDocument.CONTENT_TYPE))
                .assertNext(response -> {
                    assertEquals(200, response.getStatusCode());
                    MsmlDocument respDoc = parser.parse(response.getBodyAsString());
                    assertTrue(respDoc.isResult());
                    assertEquals(400, respDoc.results().getFirst().responseCode());
                })
                .verifyComplete();
    }

    @Test
    void testOnInfoUnsupportedContentType() {
        SipRequest request = SipRequest.builder(SipMethod.INFO, "sip:msml@127.0.0.1:5060")
                .callId("bad-ct")
                .contentType("text/plain")
                .body("Hello World")
                .build();

        StepVerifier.create(controller.onInfo(request, "bad-ct", "Hello World", "text/plain"))
                .assertNext(response -> assertEquals(415, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void testOnByeReleasesResources() throws Exception {
        rtpManager.createSession("call-bye-test");
        assertTrue(rtpManager.findSession("call-bye-test").isPresent());

        SipRequest request = SipRequest.builder(SipMethod.BYE, "sip:msml@127.0.0.1:5060")
                .callId("call-bye-test")
                .build();

        StepVerifier.create(controller.onBye(request, "call-bye-test"))
                .assertNext(response -> assertEquals(200, response.getStatusCode()))
                .verifyComplete();

        assertFalse(rtpManager.findSession("call-bye-test").isPresent());
    }
}
