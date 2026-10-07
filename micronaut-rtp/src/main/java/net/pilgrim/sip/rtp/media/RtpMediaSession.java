package net.pilgrim.sip.rtp.media;

import io.netty.channel.Channel;
import net.pilgrim.sip.rtp.RtpPacket;
import net.pilgrim.sip.rtp.RtpPacketizer;
import net.pilgrim.sip.rtp.codec.G711UlawCodec;
import net.pilgrim.sip.rtp.codec.RtpCodec;
import net.pilgrim.sip.rtp.transport.RtpInboundPacket;
import net.pilgrim.sip.rtp.transport.RtpOutboundPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Manages the active bidirectional media stream for a single call session.
 */
public final class RtpMediaSession implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(RtpMediaSession.class);

    private final String callId;
    private final int localPort;
    private final Channel channel;
    private final MediaPortManager portManager;
    private final Sinks.Many<RtpInboundPacket> incomingSink;
    private final RtpPacketizer packetizer;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile InetSocketAddress remoteAddress;
    private volatile RtpCodec codec;

    private final List<AudioProcessor> inboundProcessors = new CopyOnWriteArrayList<>();
    private final List<AudioProcessor> outboundProcessors = new CopyOnWriteArrayList<>();

    private final LongAdder packetsSent = new LongAdder();
    private final LongAdder packetsReceived = new LongAdder();
    private final LongAdder bytesSent = new LongAdder();
    private final LongAdder bytesReceived = new LongAdder();

    private final java.util.concurrent.atomic.AtomicReference<RtpAudioPlayer> activePlayer = new java.util.concurrent.atomic.AtomicReference<>();

    public RtpMediaSession(String callId,
                           int localPort,
                           Channel channel,
                           MediaPortManager portManager,
                           Sinks.Many<RtpInboundPacket> incomingSink,
                           RtpCodec initialCodec,
                           InetSocketAddress initialRemoteAddress) {
        this.callId = Objects.requireNonNull(callId, "callId");
        this.localPort = localPort;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.portManager = Objects.requireNonNull(portManager, "portManager");
        this.incomingSink = Objects.requireNonNull(incomingSink, "incomingSink");
        this.codec = initialCodec != null ? initialCodec : new G711UlawCodec();
        this.remoteAddress = initialRemoteAddress;
        this.packetizer = new RtpPacketizer(codec.payloadType(), codec.clockRate());
    }

    public String getCallId() {
        return callId;
    }

    public int getLocalPort() {
        return localPort;
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public void setRemoteAddress(InetSocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public RtpCodec getCodec() {
        return codec;
    }

    public void setCodec(RtpCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public RtpPacketizer getPacketizer() {
        return packetizer;
    }

    public Channel getChannel() {
        return channel;
    }

    public Flux<RtpInboundPacket> incomingPackets() {
        return incomingSink.asFlux();
    }

    public Flux<RtpPacket> incomingRtpPackets() {
        return incomingSink.asFlux().map(RtpInboundPacket::packet);
    }

    /**
     * Emits a reactive stream of decoded audio frames (PCM-16LE) for this media session.
     */
    public Flux<AudioFrame> incomingAudioFrames() {
        return incomingPackets().map(inbound -> {
            byte[] pcm = codec.decodeToPcm16Le(inbound.packet().getPayload());
            return new AudioFrame(callId, pcm, codec.clockRate(), 1,
                    inbound.packet().getTimestamp(), inbound.packet().getSequenceNumber(),
                    AudioFrame.Direction.INBOUND);
        });
    }

    public void addAudioProcessor(AudioProcessor processor) {
        addInboundProcessor(processor);
    }

    public void addInboundProcessor(AudioProcessor processor) {
        if (processor != null) {
            inboundProcessors.add(processor);
        }
    }

    public void addOutboundProcessor(AudioProcessor processor) {
        if (processor != null) {
            outboundProcessors.add(processor);
        }
    }

    public void removeAudioProcessor(AudioProcessor processor) {
        if (processor != null) {
            inboundProcessors.remove(processor);
            outboundProcessors.remove(processor);
        }
    }

    public void clearAudioProcessors() {
        inboundProcessors.clear();
        outboundProcessors.clear();
    }

    public List<AudioProcessor> getInboundProcessors() {
        return Collections.unmodifiableList(inboundProcessors);
    }

    public List<AudioProcessor> getOutboundProcessors() {
        return Collections.unmodifiableList(outboundProcessors);
    }

    public void onInboundPacket(RtpInboundPacket inbound) {
        packetsReceived.increment();
        bytesReceived.add(inbound.packet().getPayload().length + 12);
        if (remoteAddress == null) {
            this.remoteAddress = inbound.senderAddress();
        }
        if (!inboundProcessors.isEmpty()) {
            try {
                byte[] pcm = codec.decodeToPcm16Le(inbound.packet().getPayload());
                AudioFrame frame = new AudioFrame(callId, pcm, codec.clockRate(), 1,
                        inbound.packet().getTimestamp(), inbound.packet().getSequenceNumber(),
                        AudioFrame.Direction.INBOUND);
                for (AudioProcessor processor : inboundProcessors) {
                    try {
                        processor.process(frame);
                    } catch (Throwable t) {
                        LOG.warn("AudioProcessor error on inbound Call-ID: {}", callId, t);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to decode inbound audio for processor on Call-ID: {}", callId, e);
            }
        }
        incomingSink.tryEmitNext(inbound);
    }

    public Mono<Void> sendPacket(RtpPacket packet) {
        return sendPacket(packet, this.remoteAddress);
    }

    public Mono<Void> sendPacket(RtpPacket packet, InetSocketAddress target) {
        Objects.requireNonNull(packet, "packet");
        if (target == null) {
            return Mono.error(new IllegalStateException("No remote RTP target address configured for Call-ID: " + callId));
        }
        return Mono.create(sink -> {
            channel.writeAndFlush(new RtpOutboundPacket(packet, target)).addListener(future -> {
                if (future.isSuccess()) {
                    packetsSent.increment();
                    bytesSent.add(packet.getPayload().length + 12);
                    sink.success();
                } else {
                    sink.error(future.cause());
                }
            });
        });
    }

    public Mono<Void> sendEncodedFrame(byte[] encodedPayload, int sampleCount, boolean marker) {
        RtpPacket packet = packetizer.packetize(encodedPayload, sampleCount, marker);
        return sendPacket(packet);
    }

    public Mono<Void> sendAudioFrame(byte[] pcm16Le, boolean marker) {
        Objects.requireNonNull(pcm16Le, "pcm16Le");
        if (!outboundProcessors.isEmpty()) {
            AudioFrame frame = new AudioFrame(callId, pcm16Le, codec.clockRate(), 1,
                    packetizer.getCurrentTimestamp(), packetizer.getCurrentSequenceNumber(),
                    AudioFrame.Direction.OUTBOUND);
            for (AudioProcessor processor : outboundProcessors) {
                try {
                    processor.process(frame);
                } catch (Throwable t) {
                    LOG.warn("AudioProcessor error on outbound Call-ID: {}", callId, t);
                }
            }
        }
        byte[] encoded = codec.encodePcm16Le(pcm16Le);
        int sampleCount = pcm16Le.length / 2;
        return sendEncodedFrame(encoded, sampleCount, marker);
    }

    public long getPacketsSent() {
        return packetsSent.sum();
    }

    public long getPacketsReceived() {
        return packetsReceived.sum();
    }

    public long getBytesSent() {
        return bytesSent.sum();
    }

    public long getBytesReceived() {
        return bytesReceived.sum();
    }

    public synchronized void playAudio(byte[] pcmAudio, Runnable onComplete) {
        stopAudio();
        if (pcmAudio == null || pcmAudio.length == 0) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        java.util.concurrent.atomic.AtomicReference<RtpAudioPlayer> ref = new java.util.concurrent.atomic.AtomicReference<>();
        RtpAudioPlayer player = new RtpAudioPlayer(this, pcmAudio, () -> {
            activePlayer.compareAndSet(ref.get(), null);
            if (onComplete != null) {
                onComplete.run();
            }
        });
        ref.set(player);
        activePlayer.set(player);
        player.start();
    }

    public synchronized void stopAudio() {
        RtpAudioPlayer player = activePlayer.getAndSet(null);
        if (player != null) {
            player.stop();
        }
    }

    public boolean isAudioPlaying() {
        RtpAudioPlayer player = activePlayer.get();
        return player != null && player.isRunning();
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stopAudio();
            incomingSink.tryEmitComplete();
            channel.close().addListener(f -> portManager.releasePort(localPort));
        }
    }
}
