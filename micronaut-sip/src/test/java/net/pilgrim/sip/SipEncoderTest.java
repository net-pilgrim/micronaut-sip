package net.pilgrim.sip;

import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.parser.SipEncoder;
import net.pilgrim.sip.parser.SipParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SipEncoderTest {

    private SipEncoder encoder;
    private SipParser parser;

    @BeforeEach
    void setUp() {
        encoder = new SipEncoder();
        parser = new SipParser();
    }

    @Test
    void testEncodeAndDecodeRequest() {
        SipRequest req = SipRequest.builder(SipMethod.INVITE, "sip:bob@biloxi.com")
                .from("<sip:alice@atlanta.com>;tag=12345")
                .to("<sip:bob@biloxi.com>")
                .callId("my-call-id-1")
                .cseq(1, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bKxyz")
                .contentType("application/sdp")
                .body("v=0\r\no=alice...")
                .build();

        byte[] encoded = encoder.encode(req);
        assertNotNull(encoded);

        SipRequest parsed = (SipRequest) parser.parse(encoded);
        assertEquals(SipMethod.INVITE, parsed.getMethod());
        assertEquals("sip:bob@biloxi.com", parsed.getUri().toString());
        assertEquals("<sip:alice@atlanta.com>;tag=12345", parsed.getFrom());
        assertEquals("<sip:bob@biloxi.com>", parsed.getTo());
        assertEquals("my-call-id-1", parsed.getCallId());
        assertEquals("1 INVITE", parsed.getCSeq());
        assertEquals("application/sdp", parsed.getContentType());
        assertEquals("v=0\r\no=alice...", parsed.getBodyAsString());
        assertEquals("v=0\r\no=alice...".length(), parsed.getContentLength());
    }

    @Test
    void testCreateResponseRfc3261Compliance() {
        SipRequest req = SipRequest.builder(SipMethod.INVITE, "sip:bob@biloxi.com")
                .from("<sip:alice@atlanta.com>;tag=caller-tag")
                .to("<sip:bob@biloxi.com>")
                .callId("call-abc-123")
                .cseq(10, SipMethod.INVITE)
                .via("SIP/2.0/UDP proxy1.com;branch=z9hG4bKp1")
                .via("SIP/2.0/UDP client.com;branch=z9hG4bKc1")
                .build();

        // 100 Trying should NOT add To tag if not present
        SipResponse trying = req.createResponse(100);
        assertEquals("<sip:bob@biloxi.com>", trying.getTo());
        assertEquals(2, trying.getHeaders().getVias().size());
        assertEquals("SIP/2.0/UDP proxy1.com;branch=z9hG4bKp1", trying.getHeaders().getVias().get(0));
        assertEquals("SIP/2.0/UDP client.com;branch=z9hG4bKc1", trying.getHeaders().getVias().get(1));

        // 200 OK MUST add To tag if not present
        SipResponse ok = req.createResponse(200);
        assertTrue(ok.getTo().contains("tag="));
        assertEquals(req.getFrom(), ok.getFrom());
        assertEquals(req.getCallId(), ok.getCallId());
        assertEquals(req.getCSeq(), ok.getCSeq());
        assertEquals(2, ok.getHeaders().getVias().size());
        assertEquals(0, ok.getContentLength());
    }
}
