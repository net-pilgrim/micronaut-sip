package net.pilgrim.sip.rtp.media;

import net.pilgrim.sip.rtp.RtpPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Rfc4733DtmfHandlerTest {

    @Test
    void testProcessValidPacket() {
        List<Character> detected = new ArrayList<>();
        Rfc4733DtmfHandler handler = new Rfc4733DtmfHandler(detected::add);

        // DTMF digit '7' is event code 7
        byte[] payload = Rfc4733DtmfHandler.createPayload(7, false, 10, 160);
        RtpPacket packet = new RtpPacket(true, 101, 100, 1000L, 12345L, payload);

        boolean processed = handler.processPacket(packet);
        assertTrue(processed);
        assertEquals(1, detected.size());
        assertEquals('7', detected.get(0));
    }

    @Test
    void testDebouncingSameTimestamp() {
        List<Character> detected = new ArrayList<>();
        Rfc4733DtmfHandler handler = new Rfc4733DtmfHandler(detected::add);

        // Same timestamp 1000L across multiple intermediate packets and end packet
        byte[] p1 = Rfc4733DtmfHandler.createPayload(9, false, 10, 160);
        byte[] p2 = Rfc4733DtmfHandler.createPayload(9, false, 10, 320);
        byte[] p3 = Rfc4733DtmfHandler.createPayload(9, true, 10, 800);

        handler.processPacket(new RtpPacket(true, 101, 1, 1000L, 12345L, p1));
        handler.processPacket(new RtpPacket(false, 101, 2, 1000L, 12345L, p2));
        handler.processPacket(new RtpPacket(false, 101, 3, 1000L, 12345L, p3));

        // Exactly one event dispatched for this timestamp
        assertEquals(1, detected.size());
        assertEquals('9', detected.get(0));

        // Subsequent keypress with different timestamp 2000L
        byte[] p4 = Rfc4733DtmfHandler.createPayload(0, false, 10, 160);
        handler.processPacket(new RtpPacket(true, 101, 4, 2000L, 12345L, p4));

        assertEquals(2, detected.size());
        assertEquals('0', detected.get(1));
    }

    @Test
    void testCreatePacketSequence() {
        List<RtpPacket> packets = Rfc4733DtmfHandler.createPacketSequence(
                '#', 100, 101, 8000, 9999L, 50, 5000L);

        assertNotNull(packets);
        assertFalse(packets.isEmpty());

        // First packet must have marker bit set
        assertTrue(packets.get(0).isMarker());
        assertEquals(101, packets.get(0).getPayloadType());
        assertEquals(5000L, packets.get(0).getTimestamp());
        assertEquals(9999L, packets.get(0).getSsrc());

        // Last 3 packets must have End bit set (E = 1)
        int size = packets.size();
        assertTrue(size >= 4);
        for (int i = size - 3; i < size; i++) {
            byte[] payload = packets.get(i).getPayload();
            assertTrue((payload[1] & 0x80) != 0, "Last packets must have End bit set");
        }
    }

    @Test
    void testMalformedPacketHandling() {
        Rfc4733DtmfHandler handler = new Rfc4733DtmfHandler();
        assertFalse(handler.processPacket(null));

        RtpPacket shortPacket = new RtpPacket(false, 101, 1, 1000L, 12345L, new byte[2]);
        assertFalse(handler.processPacket(shortPacket));
    }
}
