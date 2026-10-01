package net.pilgrim.sip;

import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.parser.SipMessageTooLargeException;
import net.pilgrim.sip.parser.SipParseException;
import net.pilgrim.sip.parser.SipParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class SipParserFuzzTest {

    private SipParser parser;

    @BeforeEach
    void setUp() {
        parser = new SipParser(4096, 50, 1024);
    }

    @Test
    void testMalformedInputsFailGracefully() {
        String[] inputs = {
                "",
                "   ",
                "\r\n\r\n",
                "INVITE",
                "INVITE sip:bob@biloxi.com",
                "INVITE sip:bob@biloxi.com SIP/2.0", // Missing CRLF
                "SIP/2.0 200",
                "NOT_A_SIP_PACKET\r\n\r\n",
                "INVITE sip:bob@biloxi.com SIP/2.0\r\nMalformedHeaderWithoutColon\r\n\r\n",
                "INVITE sip:bob@biloxi.com SIP/2.0\r\nContent-Length: not-a-number\r\n\r\n",
                "INVITE sip:bob@biloxi.com SIP/2.0\r\nContent-Length: -5\r\n\r\n",
                "\0\0\0\0\0\0\0\0\r\n\r\n"
        };
        for (String input : inputs) {
            assertThrows(SipParseException.class, () -> parser.parse(input), "Expected SipParseException on: " + input);
        }
    }

    @Test
    void testMessageTooLargeEnforcement() {
        SipParser strictParser = new SipParser(500, 20, 200);

        StringBuilder sb = new StringBuilder("INVITE sip:bob@biloxi.com SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-1\r\n");
        sb.append("To: <sip:bob@biloxi.com>\r\n");
        sb.append("From: <sip:alice@atlanta.com>;tag=19283\r\n");
        sb.append("Call-ID: call-oversized\r\n");
        sb.append("CSeq: 1 INVITE\r\n");
        sb.append("Content-Length: 0\r\n");
        // Pad with extra headers to exceed 500 bytes limit
        for (int i = 0; i < 20; i++) {
            sb.append("X-Padding-Header-").append(i).append(": some-long-value-padding-content-string\r\n");
        }
        sb.append("\r\n");

        assertThrows(SipMessageTooLargeException.class, () -> strictParser.parse(sb.toString()));
    }

    @Test
    void testMaxHeaderCountEnforcement() {
        SipParser countParser = new SipParser(65536, 10, 1024);

        StringBuilder sb = new StringBuilder("INVITE sip:bob@biloxi.com SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-1\r\n");
        for (int i = 0; i < 15; i++) {
            sb.append("X-Header-").append(i).append(": val\r\n");
        }
        sb.append("\r\n");

        assertThrows(SipParseException.class, () -> countParser.parse(sb.toString()));
    }

    @Test
    void testMaxHeaderSizeEnforcement() {
        SipParser sizeParser = new SipParser(65536, 50, 100);

        String hugeHeader = "X-Huge: " + "A".repeat(150) + "\r\n";
        String packet = "INVITE sip:bob@biloxi.com SIP/2.0\r\n" + hugeHeader + "\r\n";

        assertThrows(SipParseException.class, () -> sizeParser.parse(packet));
    }

    @Test
    void testRandomFuzzingNeverCrashesWithUnexpectedExceptions() {
        Random random = new Random(42);
        byte[] buffer = new byte[256];

        for (int iteration = 0; iteration < 200; iteration++) {
            random.nextBytes(buffer);
            String fuzz = new String(buffer, StandardCharsets.ISO_8859_1);
            try {
                parser.parse(fuzz);
            } catch (SipParseException expected) {
                // Expected parsing failure
            } catch (Exception e) {
                fail("Parser threw unexpected exception type: " + e.getClass().getName() + " on input: " + fuzz, e);
            }
        }
    }

    @Test
    void testValidRequestWithBodyAndFoldedHeaders() {
        String raw = "INVITE sip:bob@biloxi.com SIP/2.0\r\n" +
                "Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-776\r\n" +
                "From: Alice <sip:alice@atlanta.com>;\r\n tag=1928301774\r\n" + // Folded header
                "To: Bob <sip:bob@biloxi.com>\r\n" +
                "Call-ID: a84b4c76e66710\r\n" +
                "CSeq: 314159 INVITE\r\n" +
                "Content-Type: application/sdp\r\n" +
                "Content-Length: 14\r\n\r\n" +
                "v=0\r\no=alice\r\n";

        SipMessage msg = parser.parse(raw);
        assertTrue(msg.isRequest());
        SipRequest req = (SipRequest) msg;
        assertEquals("sip:bob@biloxi.com", req.getUri().toString());
        assertEquals("314159 INVITE", req.getCSeq());
        assertEquals(314159L, req.getCSeqNumber());
        assertEquals("INVITE", req.getCSeqMethod());
        assertEquals("z9hG4bK-776", req.getBranch());
        assertEquals("14", req.getHeaders().get("Content-Length"));
        assertEquals("v=0\r\no=alice\r\n", req.getBodyAsString());
    }

    @Test
    void testValidResponseParsing() {
        String raw = "SIP/2.0 487 Request Terminated\r\n" +
                "Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-776\r\n" +
                "From: Alice <sip:alice@atlanta.com>;tag=1928301774\r\n" +
                "To: Bob <sip:bob@biloxi.com>;tag=998877\r\n" +
                "Call-ID: a84b4c76e66710\r\n" +
                "CSeq: 314159 INVITE\r\n" +
                "Content-Length: 0\r\n\r\n";

        SipMessage msg = parser.parse(raw);
        assertFalse(msg.isRequest());
        SipResponse resp = (SipResponse) msg;
        assertEquals(487, resp.getStatusCode());
        assertEquals("Request Terminated", resp.getReasonPhrase());
        assertTrue(resp.isClientError());
        assertTrue(resp.isFinal());
    }
}
