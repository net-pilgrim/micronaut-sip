package net.pilgrim.msml;

import net.pilgrim.msml.builder.MsmlBuilder;
import net.pilgrim.msml.client.MsmlClient;
import net.pilgrim.msml.model.AudioMixConfig;
import net.pilgrim.msml.model.MsmlDocument;
import net.pilgrim.msml.model.StreamDirection;
import net.pilgrim.msml.model.result.MsmlEvent;
import net.pilgrim.msml.model.result.MsmlResult;
import net.pilgrim.sip.client.ReactiveSipClient;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MsmlClientTest {

    @Test
    void testMsmlClientSendsPlayAndReceivesOkResult() {
        AtomicReference<SipRequest> sentRequest = new AtomicReference<>();
        InetSocketAddress msAddress = new InetSocketAddress("127.0.0.1", 5060);

        // Subclass ReactiveSipClient for test stubbing without full network
        ReactiveSipClient stubClient = new ReactiveSipClient(null, null, null) {
            @Override
            public Mono<SipResponse> send(SipRequest request, InetSocketAddress destination) {
                sentRequest.set(request);
                SipResponse ok = request.createResponse(200, "OK");
                ok.getHeaders().setContentType(MsmlDocument.CONTENT_TYPE);
                ok.setBody(MsmlBuilder.resultOk().id("play-1").toXml());
                return Mono.just(ok);
            }
        };

        MsmlClient client = new MsmlClient(stubClient);

        StepVerifier.create(client.playAudio("call-100", msAddress, "tone:440,500"))
                .assertNext(result -> {
                    assertTrue(result.isSuccess());
                    assertEquals(200, result.responseCode());
                })
                .verifyComplete();

        assertNotNull(sentRequest.get());
        assertEquals("call-100", sentRequest.get().getCallId());
        assertEquals(MsmlDocument.CONTENT_TYPE, sentRequest.get().getContentType());
        assertTrue(sentRequest.get().getBodyAsString().contains("<dialogstart target=\"conn:call-100\""));
        assertTrue(sentRequest.get().getBodyAsString().contains("<audio uri=\"tone:440,500\""));
    }

    @Test
    void testMsmlClientConferenceFlow() {
        AtomicReference<SipRequest> sentRequest = new AtomicReference<>();
        InetSocketAddress msAddress = new InetSocketAddress("127.0.0.1", 5060);

        ReactiveSipClient stubClient = new ReactiveSipClient(null, null, null) {
            @Override
            public Mono<SipResponse> send(SipRequest request, InetSocketAddress destination) {
                sentRequest.set(request);
                SipResponse ok = request.createResponse(200, "OK");
                ok.getHeaders().setContentType(MsmlDocument.CONTENT_TYPE);
                ok.setBody(MsmlBuilder.resultOk().id("conf-room1").toXml());
                return Mono.just(ok);
            }
        };

        MsmlClient client = new MsmlClient(stubClient);

        StepVerifier.create(client.createConference(msAddress, "room1", AudioMixConfig.standardPcm()))
                .assertNext(res -> assertTrue(res.isSuccess()))
                .verifyComplete();

        assertTrue(sentRequest.get().getBodyAsString().contains("<createconference id=\"room1\""));

        StepVerifier.create(client.join(msAddress, "call-1", "room1", StreamDirection.SENDRECV))
                .assertNext(res -> assertTrue(res.isSuccess()))
                .verifyComplete();

        assertTrue(sentRequest.get().getBodyAsString().contains("<join id1=\"conn:call-1\" id2=\"conf:room1\""));

        StepVerifier.create(client.unjoin(msAddress, "call-1", "room1"))
                .assertNext(res -> assertTrue(res.isSuccess()))
                .verifyComplete();

        assertTrue(sentRequest.get().getBodyAsString().contains("<unjoin id1=\"conn:call-1\" id2=\"conf:room1\""));

        StepVerifier.create(client.destroyConference(msAddress, "room1"))
                .assertNext(res -> assertTrue(res.isSuccess()))
                .verifyComplete();

        assertTrue(sentRequest.get().getBodyAsString().contains("<destroyconference id=\"room1\""));
    }

    @Test
    void testMsmlClientEventSink() {
        MsmlClient client = new MsmlClient(null);

        StepVerifier.create(client.events().take(2))
                .then(() -> {
                    client.onEventReceived(MsmlEvent.playComplete("dlg-1"));
                    client.onEventReceived(MsmlEvent.dtmfMatch("dlg-2", "1234"));
                })
                .assertNext(e -> {
                    assertEquals(MsmlEvent.PLAY_COMPLETE, e.name());
                    assertEquals("dlg-1", e.id());
                })
                .assertNext(e -> {
                    assertEquals(MsmlEvent.DTMF_MATCH, e.name());
                    assertEquals("1234", e.params().get("digits"));
                })
                .verifyComplete();
    }
}
