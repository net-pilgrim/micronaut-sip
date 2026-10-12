package net.pilgrim.sgw.asn1.inap;

import net.pilgrim.sgw.asn1.BerTlv;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InapCamelCodecTest {

    @Test
    void testTbcdNumberEncodingAndDecoding() {
        String original = "33612345678";
        byte[] bcd = InapCamelCodec.encodeTbcdNumber(original, 0x84, 0x10);

        assertNotNull(bcd);
        assertTrue(bcd.length >= 7);

        String decoded = InapCamelCodec.decodeTbcdNumber(bcd);
        assertEquals(original, decoded);
    }

    @Test
    void testInitialDPEncodingAndDecoding() {
        BerTlv idpTlv = InapCamelCodec.encodeInitialDP(10, "33612345678", "33698765432");
        assertNotNull(idpTlv);
        assertTrue(idpTlv.isConstructed());

        String hex = idpTlv.toHex();
        assertNotNull(hex);
        assertFalse(hex.isBlank());

        InapCamelCodec.InitialDPData data = InapCamelCodec.decodeInitialDP(idpTlv);
        assertEquals(10, data.serviceKey());
        assertEquals("33612345678", data.calledPartyNumber());
        assertEquals("33698765432", data.callingPartyNumber());
    }

    @Test
    void testConnectEncodingAndDecoding() {
        BerTlv connectTlv = InapCamelCodec.encodeConnect("33140001122");
        String dest = InapCamelCodec.decodeConnect(connectTlv);
        assertEquals("33140001122", dest);
    }

    @Test
    void testApplyChargingEncodingAndDecoding() {
        BerTlv achTlv = InapCamelCodec.encodeApplyCharging(300);
        int duration = InapCamelCodec.decodeApplyCharging(achTlv);
        assertEquals(300, duration);
    }

    @Test
    void testReleaseCallEncoding() {
        BerTlv releaseTlv = InapCamelCodec.encodeReleaseCall(16);
        assertNotNull(releaseTlv);
        assertEquals(2, releaseTlv.getLength());
        assertEquals("04028090", releaseTlv.toHex());
    }

    @Test
    void testPlayAnnouncementEncodingAndDecoding() {
        BerTlv paTlv = InapCamelCodec.encodePlayAnnouncement(101, "Welcome to INAP", 2, 15, true, true);
        assertNotNull(paTlv);
        assertTrue(paTlv.isConstructed());

        InapCamelCodec.PlayAnnouncementData data = InapCamelCodec.decodePlayAnnouncement(paTlv);
        assertEquals("Welcome to INAP", data.announcementText());
        assertEquals(2, data.numberOfLoops());
        assertEquals(15, data.duration());
        assertTrue(data.disconnectFromIpForbidden());
        assertTrue(data.requestAnnouncementComplete());

        // Test with integer messageId only
        BerTlv idOnly = InapCamelCodec.encodePlayAnnouncement(202, 1, false);
        InapCamelCodec.PlayAnnouncementData idData = InapCamelCodec.decodePlayAnnouncement(idOnly);
        assertEquals(202, idData.messageId());
        assertEquals(1, idData.numberOfLoops());
        assertFalse(idData.requestAnnouncementComplete());
    }

    @Test
    void testPromptAndCollectUserInfoEncodingAndDecoding() {
        BerTlv pacTlv = InapCamelCodec.encodePromptAndCollectUserInfo(
                301, "Enter PIN", 1, 4, "#", "*", 10, 5, true, true);
        assertNotNull(pacTlv);

        InapCamelCodec.PromptAndCollectData data = InapCamelCodec.decodePromptAndCollectUserInfo(pacTlv);
        assertEquals(1, data.minDigits());
        assertEquals(4, data.maxDigits());
        assertEquals("#", data.endOfReplyDigit());
        assertEquals("*", data.cancelDigit());
        assertEquals(10, data.firstDigitTimeout());
        assertEquals(5, data.interDigitTimeout());
        assertTrue(data.interruptable());
        assertTrue(data.disconnectFromIpForbidden());
        assertEquals("Enter PIN", data.announcementText());
    }

    @Test
    void testDigitsResponseEncodingAndDecoding() {
        BerTlv digitsTlv = InapCamelCodec.encodeDigitsResponse("9876#");
        assertNotNull(digitsTlv);

        String decoded = InapCamelCodec.decodeDigitsResponse(digitsTlv);
        assertEquals("9876#", decoded);
    }
}
