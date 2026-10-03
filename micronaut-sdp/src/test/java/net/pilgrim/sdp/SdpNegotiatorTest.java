package net.pilgrim.sdp;

import net.pilgrim.sip.sdp.SdpMessage;
import net.pilgrim.sip.sdp.SdpNegotiator;
import net.pilgrim.sip.sdp.SdpParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdpNegotiatorTest {

    private final SdpParser parser = new SdpParser();
    private final SdpNegotiator negotiator = new SdpNegotiator();

    @Test
    void parsesAudioOfferWithDirectionAndFormats() {
        String offer =
                "v=0\r\n" +
                        "o=Alice 1000 1000 IN IP4 127.0.0.1\r\n" +
                        "s=Offer\r\n" +
                        "c=IN IP4 127.0.0.1\r\n" +
                        "t=0 0\r\n" +
                        "m=audio 30000 RTP/AVP 0 8\r\n" +
                        "a=rtpmap:0 PCMU/8000\r\n" +
                        "a=rtpmap:8 PCMA/8000\r\n" +
                        "a=sendonly\r\n";

        SdpMessage parsed = parser.parse(offer);
        assertEquals("0", parsed.getVersion());
        SdpMessage.MediaDescription audio = parsed.findFirstAudioMedia();
        assertNotNull(audio);
        assertEquals(30000, audio.getPort());
        assertEquals("sendonly", audio.getDirectionAttribute());
        assertEquals(2, audio.getFormats().size());
        assertEquals("0", audio.getFormats().get(0));
        assertEquals("8", audio.getFormats().get(1));
    }

    @Test
    void createsAnswerWithInverseDirectionAndSharedCodec() {
        String offer =
                "v=0\r\n" +
                        "o=Alice 1000 1000 IN IP4 127.0.0.1\r\n" +
                        "s=Offer\r\n" +
                        "c=IN IP4 127.0.0.1\r\n" +
                        "t=0 0\r\n" +
                        "m=audio 30000 RTP/AVP 8 0\r\n" +
                        "a=rtpmap:8 PCMA/8000\r\n" +
                        "a=rtpmap:0 PCMU/8000\r\n" +
                        "a=sendonly\r\n";

        String answer = negotiator.createAnswer(offer);
        SdpMessage parsedAnswer = parser.parse(answer);
        SdpMessage.MediaDescription audio = parsedAnswer.findFirstAudioMedia();
        assertNotNull(audio);
        assertEquals("recvonly", audio.getDirectionAttribute());
        assertEquals("8", audio.getFormats().get(0));
        assertTrue(answer.contains("a=rtpmap:8 PCMA/8000"));
    }

    @Test
    void createsOfferWithAudioAndDefaultCodec() {
        String offer = negotiator.createOffer();
        SdpMessage parsed = parser.parse(offer);
        SdpMessage.MediaDescription audio = parsed.findFirstAudioMedia();
        assertNotNull(audio);
        assertEquals("audio", audio.getMedia());
        assertTrue(audio.getFormats().contains("0"));
        assertEquals("sendrecv", audio.getDirectionAttribute());
    }
}
