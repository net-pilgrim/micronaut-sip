package net.pilgrim.sip.rtp.media;

import net.pilgrim.sip.rtp.RtpPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Handles RFC 4733 (and RFC 2833) {@code telephone-event} DTMF RTP payload decoding and encoding.
 * <p>
 * Decodes inbound 4-byte RTP telephone-event packets, debouncing them so each keypress
 * notifies listeners exactly once. Also supports creating RFC 4733 packet sequences
 * for transmitting DTMF digits over RTP.
 */
public class Rfc4733DtmfHandler {

    private static final Logger LOG = LoggerFactory.getLogger(Rfc4733DtmfHandler.class);

    public static final int DEFAULT_PAYLOAD_TYPE = 101;
    public static final int DEFAULT_CLOCK_RATE = 8000;
    public static final int DEFAULT_VOLUME = 10; // dBm0

    private final List<Consumer<Character>> listeners = new CopyOnWriteArrayList<>();

    // Debounce tracking
    private long lastEventTimestamp = -1;
    private int lastEventCode = -1;

    public Rfc4733DtmfHandler() {
    }

    public Rfc4733DtmfHandler(Consumer<Character> listener) {
        if (listener != null) {
            addListener(listener);
        }
    }

    public void addListener(Consumer<Character> listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<Character> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void clearListeners() {
        listeners.clear();
    }

    /**
     * Processes an inbound RFC 4733 RTP packet.
     *
     * @param packet RTP packet containing telephone-event payload
     * @return true if a valid DTMF packet was parsed
     */
    public boolean processPacket(RtpPacket packet) {
        if (packet == null) {
            return false;
        }

        byte[] payload = packet.getPayload();
        if (payload == null || payload.length < 4) {
            LOG.warn("Received malformed telephone-event packet (payload length: {})",
                    payload == null ? 0 : payload.length);
            return false;
        }

        int eventCode = payload[0] & 0xFF;
        boolean endBit = (payload[1] & 0x80) != 0;
        int volume = payload[1] & 0x3F;
        int durationTicks = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        long timestamp = packet.getTimestamp();

        char digit;
        try {
            digit = DtmfToneGenerator.eventCodeToChar(eventCode);
        } catch (IllegalArgumentException e) {
            LOG.debug("Unsupported telephone event code: {}", eventCode);
            return false;
        }

        // RFC 4733 Debouncing: all packets for a single keypress share the same RTP timestamp
        if (timestamp != lastEventTimestamp) {
            // New DTMF event started
            lastEventTimestamp = timestamp;
            lastEventCode = eventCode;
            LOG.debug("RFC 4733 DTMF digit detected: '{}' (event={}, vol={}, dur={}, ts={})",
                    digit, eventCode, volume, durationTicks, timestamp);
            dispatchDigit(digit);
        } else {
            // Continuation or retransmission of existing event
            if (endBit) {
                LOG.trace("RFC 4733 End packet for digit '{}' (ts={})", digit, timestamp);
            }
        }

        return true;
    }

    private void dispatchDigit(char digit) {
        for (Consumer<Character> listener : listeners) {
            try {
                listener.accept(digit);
            } catch (Throwable t) {
                LOG.error("Error notifying DTMF listener for RFC 4733 digit '{}'", digit, t);
            }
        }
    }

    /**
     * Resets internal debounce state.
     */
    public synchronized void reset() {
        lastEventTimestamp = -1;
        lastEventCode = -1;
    }

    /**
     * Constructs a 4-byte RFC 4733 payload.
     *
     * @param eventCode     DTMF event code (0-15)
     * @param endBit        true if this is an end packet (bit 7 of byte 1 set)
     * @param volume        volume level (0-63)
     * @param durationTicks tone duration in timestamp clock units
     * @return 4-byte payload
     */
    public static byte[] createPayload(int eventCode, boolean endBit, int volume, int durationTicks) {
        byte[] payload = new byte[4];
        payload[0] = (byte) (eventCode & 0xFF);
        payload[1] = (byte) ((endBit ? 0x80 : 0x00) | (volume & 0x3F));
        payload[2] = (byte) ((durationTicks >> 8) & 0xFF);
        payload[3] = (byte) (durationTicks & 0xFF);
        return payload;
    }

    /**
     * Generates a standard RFC 4733 packet train for a DTMF digit.
     * Emits packets at ~20ms intervals and concludes with 3 end packets (per RFC 4733 section 2.5.1.4).
     *
     * @param digit       DTMF digit character ('0'-'9', '*', '#', 'A'-'D')
     * @param durationMs  duration of the keypress in milliseconds (min 40ms, typically 100-160ms)
     * @param payloadType RTP payload type (e.g. 101)
     * @param clockRate   clock rate in Hz (e.g. 8000)
     * @param ssrc        RTP synchronization source identifier
     * @param initialSeq  starting sequence number
     * @param timestamp   RTP timestamp for the start of the event
     * @return list of RtpPacket objects ready for transmission
     */
    public static List<RtpPacket> createPacketSequence(char digit,
                                                       int durationMs,
                                                       int payloadType,
                                                       int clockRate,
                                                       long ssrc,
                                                       int initialSeq,
                                                       long timestamp) {
        int eventCode = DtmfToneGenerator.charToEventCode(digit);
        int rate = clockRate > 0 ? clockRate : DEFAULT_CLOCK_RATE;
        int pt = payloadType >= 0 ? payloadType : DEFAULT_PAYLOAD_TYPE;
        int totalTicks = (rate * Math.max(40, durationMs)) / 1000;
        int intervalTicks = (rate * 20) / 1000; // 160 ticks at 8kHz

        List<RtpPacket> packets = new ArrayList<>();
        int currentSeq = initialSeq & 0xFFFF;
        int currentDuration = intervalTicks;

        // 1. Initial and intermediate packets (E = 0)
        boolean first = true;
        while (currentDuration < totalTicks) {
            byte[] payload = createPayload(eventCode, false, DEFAULT_VOLUME, currentDuration);
            packets.add(new RtpPacket(first, pt, currentSeq, timestamp, ssrc, payload));
            first = false;
            currentSeq = (currentSeq + 1) & 0xFFFF;
            currentDuration = Math.min(totalTicks, currentDuration + intervalTicks);
        }

        // 2. Final packets with End bit set (E = 1) - sent 3 times per RFC 4733 recommendation
        for (int i = 0; i < 3; i++) {
            byte[] endPayload = createPayload(eventCode, true, DEFAULT_VOLUME, totalTicks);
            packets.add(new RtpPacket(false, pt, currentSeq, timestamp, ssrc, endPayload));
            currentSeq = (currentSeq + 1) & 0xFFFF;
        }

        return packets;
    }
}
