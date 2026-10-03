package net.pilgrim.sip.rtp.codec;

/**
 * Codec abstraction for RTP audio payloads.
 * <p>
 * Input/output PCM uses signed 16-bit little-endian samples.
 */
public interface RtpCodec {

    String name();

    int payloadType();

    int clockRate();

    byte[] encodePcm16Le(byte[] pcm16Le);

    byte[] decodeToPcm16Le(byte[] payload);
}

