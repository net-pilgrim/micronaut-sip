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
        String offer = """
                v=0
                o=Alice 1000 1000 IN IP4 127.0.0.1
                s=Offer
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 30000 RTP/AVP 0 8
                a=rtpmap:0 PCMU/8000
                a=rtpmap:8 PCMA/8000
                a=sendonly
                """.replace("\n", "\r\n");

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
        String offer = """
                v=0
                o=Alice 1000 1000 IN IP4 127.0.0.1
                s=Offer
                c=IN IP4 127.0.0.1
                t=0 0
                m=audio 30000 RTP/AVP 8 0
                a=rtpmap:8 PCMA/8000
                a=rtpmap:0 PCMU/8000
                a=sendonly
                """.replace("\n", "\r\n");

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

    @Test
    void createsOfferAndAnswerWithCustomDynamicPort() {
        String offer = negotiator.createOffer(10500);
        SdpMessage parsedOffer = parser.parse(offer);
        assertEquals(10500, parsedOffer.findFirstAudioMedia().getPort());

        String answer = negotiator.createAnswer(offer, 10502);
        SdpMessage parsedAnswer = parser.parse(answer);
        assertEquals(10502, parsedAnswer.findFirstAudioMedia().getPort());
    }

    @Test
    void createsOfferAndAnswerWithCustomAdvertisedIp() {
        String customIp = "198.51.100.25";
        String offer = negotiator.createOffer(10500, customIp);
        assertTrue(offer.contains("c=IN IP4 198.51.100.25"));
        assertTrue(offer.contains("o=MicronautSIP 2890844526 2890844526 IN IP4 198.51.100.25"));

        String answer = negotiator.createAnswer(offer, 10502, customIp);
        assertTrue(answer.contains("c=IN IP4 198.51.100.25"));
        assertTrue(answer.contains("o=MicronautSIP 2890844526 2890844526 IN IP4 198.51.100.25"));

        SdpNegotiator customNegotiator = new SdpNegotiator(customIp);
        assertEquals(customIp, customNegotiator.getLocalAddress());
        String defaultOffer = customNegotiator.createOffer();
        assertTrue(defaultOffer.contains("c=IN IP4 198.51.100.25"));
    }
}
