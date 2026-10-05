package net.pilgrim.sip.model;

import net.pilgrim.sip.dtmf.DtmfSignal;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SipMessageDtmfTest {

    @Test
    void testRequestWithDtmfRelay() {
        SipRequest request = SipRequest.builder(SipMethod.INFO, "sip:bob@127.0.0.1:5060")
                .from("<sip:alice@127.0.0.1>")
                .to("<sip:bob@127.0.0.1>")
                .dtmf(DtmfSignal.of('4', 200, 5))
                .build();

        assertTrue(request.isDtmf());
        assertEquals("application/dtmf-relay", request.getContentType());
        Optional<DtmfSignal> dtmfOpt = request.getDtmfSignal();
        assertTrue(dtmfOpt.isPresent());
        assertEquals('4', dtmfOpt.get().getDigit());
        assertEquals(200, dtmfOpt.get().getDuration());
        assertEquals(5, dtmfOpt.get().getVolume());
    }

    @Test
    void testRequestWithDtmfPlain() {
        SipRequest request = SipRequest.builder(SipMethod.INFO, "sip:bob@127.0.0.1:5060")
                .from("<sip:alice@127.0.0.1>")
                .to("<sip:bob@127.0.0.1>")
                .dtmf(DtmfSignal.of('#'), SipHeaders.APPLICATION_DTMF)
                .build();

        assertTrue(request.isDtmf());
        assertEquals("application/dtmf", request.getContentType());
        assertEquals("#", request.getBodyAsString());
        Optional<DtmfSignal> dtmfOpt = request.getDtmfSignal();
        assertTrue(dtmfOpt.isPresent());
        assertEquals('#', dtmfOpt.get().getDigit());
    }

    @Test
    void testRequestConvenienceDtmfBuilders() {
        SipRequest req1 = SipRequest.builder(SipMethod.INFO, "sip:bob@127.0.0.1:5060")
                .dtmf('8')
                .build();
        assertTrue(req1.isDtmf());
        assertEquals('8', req1.getDtmfSignal().orElseThrow().getDigit());
        assertEquals(160, req1.getDtmfSignal().orElseThrow().getDuration());

        SipRequest req2 = SipRequest.builder(SipMethod.INFO, "sip:bob@127.0.0.1:5060")
                .dtmfRelay('2', 250)
                .build();
        assertTrue(req2.isDtmf());
        assertEquals('2', req2.getDtmfSignal().orElseThrow().getDigit());
        assertEquals(250, req2.getDtmfSignal().orElseThrow().getDuration());
    }

    @Test
    void testNonDtmfRequest() {
        SipRequest request = SipRequest.builder(SipMethod.INVITE, "sip:bob@127.0.0.1:5060")
                .from("<sip:alice@127.0.0.1>")
                .to("<sip:bob@127.0.0.1>")
                .contentType("application/sdp")
                .body("""
                        v=0
                        o=alice 100 100 IN IP4 127.0.0.1
                        s=-
                        t=0 0
                        """.replace("\n", "\r\n"))
                .build();

        assertFalse(request.isDtmf());
        assertTrue(request.getDtmfSignal().isEmpty());
    }

    @Test
    void testSetDtmfOnResponse() {
        SipRequest request = SipRequest.builder(SipMethod.INFO, "sip:bob@127.0.0.1:5060").build();
        SipResponse response = request.createResponse(200);

        response.setDtmf(DtmfSignal.of('*', 160));
        assertTrue(response.isDtmf());
        assertEquals("application/dtmf-relay", response.getContentType());
        assertEquals('*', response.getDtmfSignal().orElseThrow().getDigit());
    }
}
