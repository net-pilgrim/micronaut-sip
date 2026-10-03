package net.pilgrim.sip.rtp;

import net.pilgrim.sip.rtp.codec.RtpCodec;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Objects;

/**
 * Minimal UDP RTP sender for single-stream audio.
 */
public final class RtpStreamSender implements Closeable {

    private final DatagramSocket socket;
    private final InetAddress remoteAddress;
    private final int remotePort;
    private final RtpPacketizer packetizer;

    public RtpStreamSender(InetAddress remoteAddress, int remotePort, RtpPacketizer packetizer) throws IOException {
        this(new DatagramSocket(), remoteAddress, remotePort, packetizer);
    }

    public RtpStreamSender(DatagramSocket socket, InetAddress remoteAddress, int remotePort, RtpPacketizer packetizer) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
        if (remotePort <= 0 || remotePort > 65535) {
            throw new IllegalArgumentException("remotePort must be between 1 and 65535");
        }
        this.remotePort = remotePort;
        this.packetizer = Objects.requireNonNull(packetizer, "packetizer");
    }

    public void sendPacket(RtpPacket packet) throws IOException {
        Objects.requireNonNull(packet, "packet");
        byte[] bytes = packet.toBytes();
        DatagramPacket datagram = new DatagramPacket(bytes, bytes.length, remoteAddress, remotePort);
        socket.send(datagram);
    }

    public void sendEncodedFrame(byte[] encodedPayload, int sampleCount, boolean marker) throws IOException {
        sendPacket(packetizer.packetize(encodedPayload, sampleCount, marker));
    }

    public void sendPcm16LeFrame(byte[] pcm16Le, RtpCodec codec, boolean marker) throws IOException {
        Objects.requireNonNull(codec, "codec");
        if ((pcm16Le == null) || ((pcm16Le.length & 1) != 0)) {
            throw new IllegalArgumentException("pcm16Le must be non-null and aligned to 16-bit samples");
        }
        byte[] encoded = codec.encodePcm16Le(pcm16Le);
        int sampleCount = pcm16Le.length / 2;
        sendEncodedFrame(encoded, sampleCount, marker);
    }

    @Override
    public void close() {
        socket.close();
    }
}

