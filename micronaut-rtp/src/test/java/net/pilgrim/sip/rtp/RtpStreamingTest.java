package net.pilgrim.sip.rtp;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpStreamingTest {

    @Test
    void serializesAndParsesRtpPacket() {
        RtpPacket packet = new RtpPacket(true, 0, 65535, 123_456_789L, 42L, new byte[]{1, 2, 3, 4});
        byte[] bytes = packet.toBytes();
        RtpPacket parsed = RtpPacket.parse(bytes);

        assertTrue(parsed.isMarker());
        assertEquals(0, parsed.getPayloadType());
        assertEquals(65535, parsed.getSequenceNumber());
        assertEquals(123_456_789L, parsed.getTimestamp());
        assertEquals(42L, parsed.getSsrc());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, parsed.getPayload());
    }

    @Test
    void sendsAndReceivesRtpFrameOverUdp() throws Exception {
        byte[] encodedPayload = new byte[]{11, 22, 33, 44, 55};
        RtpPacketizer packetizer = new RtpPacketizer(0, 8_000, 1000, 16_000);

        try (RtpStreamReceiver receiver = new RtpStreamReceiver(0);
             RtpStreamSender sender = new RtpStreamSender(
                     InetAddress.getByName("127.0.0.1"),
                     receiver.getLocalPort(),
                     packetizer
             )) {

            sender.sendEncodedFrame(encodedPayload, 160, true);
            RtpPacket received = receiver.receiveNext(1500, 1_000);

            assertNotNull(received);
            assertEquals(0, received.getPayloadType());
            assertEquals(1000, received.getSequenceNumber());
            assertEquals(16_000L, received.getTimestamp());
            assertArrayEquals(encodedPayload, received.getPayload());
        }
    }

    @Test
    void packetizerIncrementsSequenceAndTimestamp() {
        RtpPacketizer packetizer = new RtpPacketizer(8, 8_000, 7, 2000);
        RtpPacket first = packetizer.packetize(new byte[]{1, 1}, 160, false);
        RtpPacket second = packetizer.packetize(new byte[]{2, 2}, 160, false);

        assertEquals(7, first.getSequenceNumber());
        assertEquals(8, second.getSequenceNumber());
        assertEquals(2000L, first.getTimestamp());
        assertEquals(2160L, second.getTimestamp());
        assertTrue(Arrays.equals(new byte[]{2, 2}, second.getPayload()));
    }
}

