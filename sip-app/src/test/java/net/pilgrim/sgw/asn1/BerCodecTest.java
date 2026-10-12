package net.pilgrim.sgw.asn1;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BerCodecTest {

    @Test
    void testEncodeAndDecodeInteger() {
        BerTlv tlv = BerCodec.integer(42);
        assertEquals(BerTag.INTEGER, tlv.getTag());
        assertEquals("02012a", tlv.toHex());
        assertEquals(42, tlv.asInteger());

        BerTlv decoded = BerCodec.decodeHex("02012a");
        assertEquals(BerTag.INTEGER, decoded.getTag());
        assertEquals(42, decoded.asInteger());
    }

    @Test
    void testEncodeAndDecodeOctetString() {
        BerTlv tlv = BerCodec.octetString(BerTag.OCTET_STRING, "Hello");
        assertEquals("040548656c6c6f", tlv.toHex());
        assertEquals("Hello", tlv.asString());

        BerTlv decoded = BerCodec.decodeHex("040548656c6c6f");
        assertEquals("Hello", decoded.asString());
    }

    @Test
    void testConstructedSequence() {
        BerTlv intTlv = BerCodec.contextInteger(0, 100);
        BerTlv strTlv = BerCodec.contextOctetString(2, "Test");

        BerTlv seq = BerCodec.sequence(intTlv, strTlv);
        assertTrue(seq.isConstructed());
        assertEquals(2, seq.getChildren().size());

        String hex = seq.toHex();
        assertNotNull(hex);

        BerTlv decoded = BerCodec.decodeHex(hex);
        assertTrue(decoded.isConstructed());
        assertEquals(2, decoded.getChildren().size());

        BerTlv child0 = decoded.findChild(0).orElseThrow();
        assertEquals(100, child0.asInteger());

        BerTlv child2 = decoded.findChild(2).orElseThrow();
        assertEquals("Test", child2.asString());
    }

    @Test
    void testLongFormLengthEncoding() {
        byte[] largeValue = new byte[250];
        for (int i = 0; i < largeValue.length; i++) {
            largeValue[i] = (byte) (i & 0xFF);
        }

        BerTlv tlv = BerCodec.octetString(largeValue);
        byte[] encoded = tlv.encode();

        // Tag (1 byte) + Length (0x81, 0xFA = 2 bytes) + Value (250 bytes) = 253 bytes
        assertEquals(253, encoded.length);
        assertEquals(0x04, encoded[0]);
        assertEquals((byte) 0x81, encoded[1]);
        assertEquals((byte) 250, encoded[2]);

        BerTlv decoded = BerCodec.decode(encoded);
        assertEquals(250, decoded.getLength());
        assertArrayEquals(largeValue, decoded.getValueBytes());
    }

    @Test
    void testToMapPresentation() {
        BerTlv seq = BerCodec.sequence(
                BerCodec.integer(1),
                BerCodec.octetString(BerTag.UTF8_STRING, "world")
        );
        Map<String, Object> map = seq.toMap();
        assertTrue((Boolean) map.get("constructed"));
        assertTrue(map.containsKey("children"));
        List<?> children = (List<?>) map.get("children");
        assertEquals(2, children.size());
    }
}
