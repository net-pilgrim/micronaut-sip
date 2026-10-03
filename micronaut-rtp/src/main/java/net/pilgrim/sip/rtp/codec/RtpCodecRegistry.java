package net.pilgrim.sip.rtp.codec;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of RTP codecs by static/dynamic payload type.
 */
public final class RtpCodecRegistry {

    private final Map<Integer, RtpCodec> codecsByPayloadType = new ConcurrentHashMap<>();

    public RtpCodecRegistry register(RtpCodec codec) {
        if (codec == null) {
            throw new IllegalArgumentException("codec must not be null");
        }
        int payloadType = codec.payloadType();
        if (payloadType < 0 || payloadType > 127) {
            throw new IllegalArgumentException("payloadType must be between 0 and 127");
        }
        RtpCodec existing = codecsByPayloadType.putIfAbsent(payloadType, codec);
        if (existing != null) {
            throw new IllegalStateException("payloadType " + payloadType + " already registered: " + existing.name());
        }
        return this;
    }

    public Optional<RtpCodec> findByPayloadType(int payloadType) {
        return Optional.ofNullable(codecsByPayloadType.get(payloadType));
    }

    public static RtpCodecRegistry withG711Defaults() {
        return new RtpCodecRegistry()
                .register(new G711UlawCodec())
                .register(new G711AlawCodec());
    }
}

