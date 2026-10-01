package net.pilgrim.sip;

import net.pilgrim.sip.model.*;
import net.pilgrim.sip.parser.SipParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SipParserTest {

    private SipParser parser;

    @BeforeEach
    void setUp() {
        parser = new SipParser();
    }

    @Test
    void testParseStandardInvite() {
        String raw =
                "INVITE sip:bob@biloxi.com SIP/2.0\r\n" +
                "Via: SIP/2.0/UDP pc33.atlanta.com;branch=z9hG4bK776asdhds\r\n" +
                "Max-Forwards: 70\r\n" +
                "To: Bob <sip:bob@biloxi.com>\r\n" +
                "From: Alice <sip:alice@atlanta.com>;tag=1928301774\r\n" +
                "Call-ID: a84b4c76e66710@pc33.atlanta.com\r\n" +
                "CSeq: 314159 INVITE\r\n" +
                "Contact: <sip:alice@pc33.atlanta.com>\r\n" +
                "Content-Type: application/sdp\r\n" +
                "Content-Length: 136\r\n" +
                "\r\n" +
                "v=0\r\n" +
                "o=Alice 2890844526 2890844526 IN IP4 127.0.0.1\r\n" +
                "s=Phone\r\n" +
                "c=IN IP4 127.0.0.1\r\n" +
                "t=0 0\r\n" +
                "m=audio 49170 RTP/AVP 0\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n";

        SipMessage msg = parser.parse(raw);
        assertTrue(msg.isRequest());
        SipRequest req = (SipRequest) msg;

        assertEquals(SipMethod.INVITE, req.getMethod());
        assertEquals("sip:bob@biloxi.com", req.getUri().toString());
        assertEquals("SIP/2.0", req.getSipVersion());
        assertEquals("Bob <sip:bob@biloxi.com>", req.getTo());
        assertEquals("Alice <sip:alice@atlanta.com>;tag=1928301774", req.getFrom());
        assertEquals("a84b4c76e66710@pc33.atlanta.com", req.getCallId());
        assertEquals("314159 INVITE", req.getCSeq());
        assertEquals("application/sdp", req.getContentType());
        assertEquals(136, req.getContentLength());
        assertTrue(req.getBodyAsString().startsWith("v=0"));
    }

    @Test
    void testParseResponse() {
        String raw =
                "SIP/2.0 200 OK\r\n" +
                "Via: SIP/2.0/UDP pc33.atlanta.com;branch=z9hG4bK776asdhds\r\n" +
                "To: Bob <sip:bob@biloxi.com>;tag=a6c85cf\r\n" +
                "From: Alice <sip:alice@atlanta.com>;tag=1928301774\r\n" +
                "Call-ID: a84b4c76e66710@pc33.atlanta.com\r\n" +
                "CSeq: 314159 INVITE\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";

        SipMessage msg = parser.parse(raw);
        assertFalse(msg.isRequest());
        assertTrue(msg.isResponse());

        SipResponse resp = (SipResponse) msg;
        assertEquals(200, resp.getStatusCode());
        assertEquals("OK", resp.getReasonPhrase());
        assertTrue(resp.is2xx());
        assertTrue(resp.isSuccess());
        assertTrue(resp.isFinal());
        assertFalse(resp.isProvisional());
        assertEquals(0, resp.getContentLength());
    }

    @Test
    void testHeaderUnfolding() {
        String raw =
                "OPTIONS sip:example.com SIP/2.0\r\n" +
                "Subject: This is a very long\r\n" +
                " subject line that is unfolded\r\n" +
                "\tacross multiple lines\r\n" +
                "Call-ID: test-fold-123\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";

        SipMessage msg = parser.parse(raw);
        assertEquals("This is a very long subject line that is unfolded across multiple lines",
                msg.getHeaders().get("Subject"));
    }

    @Test
    void testCompactHeaders() {
        String raw =
                "INVITE sip:test@domain.com SIP/2.0\r\n" +
                "v: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK123\r\n" +
                "f: <sip:caller@domain.com>;tag=abc\r\n" +
                "t: <sip:callee@domain.com>\r\n" +
                "i: compact-call-id-999\r\n" +
                "m: <sip:caller@127.0.0.1:5060>\r\n" +
                "c: text/plain\r\n" +
                "l: 5\r\n" +
                "\r\n" +
                "hello";

        SipMessage msg = parser.parse(raw);
        assertEquals("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK123", msg.getVia());
        assertEquals("<sip:caller@domain.com>;tag=abc", msg.getFrom());
        assertEquals("<sip:callee@domain.com>", msg.getTo());
        assertEquals("compact-call-id-999", msg.getCallId());
        assertEquals("<sip:caller@127.0.0.1:5060>", msg.getContact());
        assertEquals("text/plain", msg.getContentType());
        assertEquals(5, msg.getContentLength());
        assertEquals("hello", msg.getBodyAsString());
    }

    @Test
    void testSipUriParsing() {
        SipUri uri = SipUri.parse("sip:alice:secret@atlanta.com:5060;transport=udp;lr?subject=project&priority=urgent");
        assertEquals("sip", uri.getScheme());
        assertEquals("alice", uri.getUser());
        assertEquals("secret", uri.getPassword());
        assertEquals("atlanta.com", uri.getHost());
        assertEquals(5060, uri.getPort());
        assertEquals("udp", uri.getParameter("transport"));
        assertTrue(uri.getParameters().containsKey("lr"));
        assertEquals("project", uri.getHeaders().get("subject"));
        assertEquals("urgent", uri.getHeaders().get("priority"));

        SipUri simple = SipUri.parse("sip:bob@example.com");
        assertEquals("bob", simple.getUser());
        assertEquals("example.com", simple.getHost());
        assertEquals(-1, simple.getPort());
    }
}
