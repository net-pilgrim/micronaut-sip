package net.pilgrim.sip.rtp.transport;

import net.pilgrim.sip.rtp.RtpPacket;
import net.pilgrim.sip.rtp.RtpPacketizer;
import net.pilgrim.sip.rtp.codec.G711UlawCodec;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RtpNettyStreamingTest {

    @Test
    void streamsRtpPacketOverNettyPipeline() throws Exception {
        InetSocketAddress bindAddr = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);

        try (RtpNettyReceiver receiver = new RtpNettyReceiver(bindAddr)) {
            int port = receiver.getLocalPort();
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", port);

            RtpPacketizer packetizer = new RtpPacketizer(0, 8000, 100, 16000);
            try (RtpNettySender sender = new RtpNettySender(target, packetizer)) {
                byte[] pcm16 = new byte[320]; // 160 samples (20ms) of silence
                sender.sendPcm16LeFrame(pcm16, new G711UlawCodec(), true).block(Duration.ofSeconds(2));

                RtpPacket received = receiver.receiveNext(Duration.ofSeconds(2));
                assertNotNull(received, "Should receive packet via Netty");
                assertEquals(0, received.getPayloadType());
                assertEquals(100, received.getSequenceNumber());
                assertEquals(16000L, received.getTimestamp());
                assertEquals(160, received.getPayload().length);
            }
        }
    }

    @Test
    void receivesInboundPacketWithSenderMetadataReactively() throws Exception {
        InetSocketAddress bindAddr = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);

        try (RtpNettyReceiver receiver = new RtpNettyReceiver(bindAddr)) {
            int port = receiver.getLocalPort();
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", port);

            RtpPacketizer packetizer = new RtpPacketizer(8, 8000, 50, 8000);
            try (RtpNettySender sender = new RtpNettySender(target, packetizer)) {
                StepVerifier.create(receiver.incomingPackets().take(1))
                        .then(() -> sender.sendEncodedFrame(new byte[]{1, 2, 3, 4}, 4, false).block(Duration.ofSeconds(2)))
                        .assertNext(inbound -> {
                            assertNotNull(inbound.senderAddress());
                            assertEquals(8, inbound.packet().getPayloadType());
                            assertEquals(50, inbound.packet().getSequenceNumber());
                            assertArrayEquals(new byte[]{1, 2, 3, 4}, inbound.packet().getPayload());
                        })
                        .verifyComplete();
            }
        }
    }
}
