package net.pilgrim.sip.rtp;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Objects;

/**
 * Minimal UDP RTP receiver that returns parsed RTP packets.
 */
public final class RtpStreamReceiver implements Closeable {

    private final DatagramSocket socket;

    public RtpStreamReceiver(int localPort) throws IOException {
        this(new DatagramSocket(new InetSocketAddress(localPort)));
    }

    public RtpStreamReceiver(DatagramSocket socket) {
        this.socket = Objects.requireNonNull(socket, "socket");
    }

    public int getLocalPort() {
        return socket.getLocalPort();
    }

    public RtpPacket receiveNext(int maxPacketSize, int timeoutMillis) throws IOException {
        if (maxPacketSize <= 0) {
            throw new IllegalArgumentException("maxPacketSize must be positive");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0");
        }
        socket.setSoTimeout(timeoutMillis);

        byte[] buffer = new byte[maxPacketSize];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(datagram);
        } catch (SocketTimeoutException e) {
            return null;
        }

        byte[] payload = new byte[datagram.getLength()];
        System.arraycopy(datagram.getData(), datagram.getOffset(), payload, 0, datagram.getLength());
        return RtpPacket.parse(payload);
    }

    @Override
    public void close() {
        socket.close();
    }
}

