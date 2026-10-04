package net.pilgrim.sip.dtmf;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a Dual-Tone Multi-Frequency (DTMF) tone / signal carried over SIP messages
 * (such as SIP INFO per RFC 2976 / RFC 6086 or SIP MESSAGE per RFC 3428).
 *
 * Supported digits: '0'-'9', '*', '#', 'A', 'B', 'C', 'D'.
 */
public final class DtmfSignal implements Serializable {

    public static final int DEFAULT_DURATION_MS = 160;
    public static final int DEFAULT_VOLUME = 0; // 0 dBm0 / reference level

    private final char digit;
    private final int duration; // in milliseconds
    private final int volume;

    public DtmfSignal(char digit) {
        this(digit, DEFAULT_DURATION_MS, DEFAULT_VOLUME);
    }

    public DtmfSignal(char digit, int duration) {
        this(digit, duration, DEFAULT_VOLUME);
    }

    public DtmfSignal(char digit, int duration, int volume) {
        if (!isValidDigit(digit)) {
            throw new IllegalArgumentException("Invalid DTMF digit: '" + digit + "'. Allowed: 0-9, *, #, A-D");
        }
        this.digit = normalizeDigit(digit);
        this.duration = duration > 0 ? duration : DEFAULT_DURATION_MS;
        this.volume = Math.max(0, volume);
    }

    public static DtmfSignal of(char digit) {
        return new DtmfSignal(digit);
    }

    public static DtmfSignal of(char digit, int duration) {
        return new DtmfSignal(digit, duration);
    }

    public static DtmfSignal of(char digit, int duration, int volume) {
        return new DtmfSignal(digit, duration, volume);
    }

    public static DtmfSignal of(String digitStr) {
        if (digitStr == null || digitStr.trim().isEmpty()) {
            throw new IllegalArgumentException("DTMF digit string cannot be null or empty");
        }
        String trimmed = digitStr.trim();
        if (trimmed.length() == 1) {
            return new DtmfSignal(trimmed.charAt(0));
        }
        return parse(trimmed);
    }

    public static boolean isValidDigit(char c) {
        return (c >= '0' && c <= '9')
                || c == '*'
                || c == '#'
                || (c >= 'A' && c <= 'D')
                || (c >= 'a' && c <= 'd');
    }

    public static char normalizeDigit(char c) {
        return Character.toUpperCase(c);
    }

    public char getDigit() {
        return digit;
    }

    public int getDuration() {
        return duration;
    }

    public int getVolume() {
        return volume;
    }

    /**
     * Formats the DTMF signal as an RFC 2976 / Cisco standard application/dtmf-relay body:
     * Signal=5
     * Duration=160
     */
    public String toRelayBody() {
        StringBuilder sb = new StringBuilder();
        sb.append("Signal=").append(digit).append("\r\n");
        sb.append("Duration=").append(duration).append("\r\n");
        if (volume > 0) {
            sb.append("Volume=").append(volume).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * Formats the DTMF signal as an application/dtmf body (plain digit).
     */
    public String toDtmfBody() {
        return String.valueOf(digit);
    }

    /**
     * Parses a DTMF signal from a message body and optional content-type.
     */
    public static DtmfSignal parse(String body) {
        return parse(body, null);
    }

    public static DtmfSignal parse(String body, String contentType) {
        return tryParse(body, contentType).orElseThrow(() ->
                new IllegalArgumentException("Failed to parse DTMF signal from body: '" + body + "' with content-type: '" + contentType + "'"));
    }

    public static Optional<DtmfSignal> tryParse(String body, String contentType) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }

        String ct = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
        String trimmed = body.trim();

        // 1. If key-value formatted (e.g. Signal=5)
        Character foundSignal = null;
        Integer foundDuration = null;
        Integer foundVolume = null;

        String[] lines = trimmed.split("[\\r\\n]+");
        boolean hasKeyValue = false;
        for (String line : lines) {
            int eqIdx = line.indexOf('=');
            int colonIdx = line.indexOf(':');
            int sepIdx = (eqIdx != -1) ? eqIdx : colonIdx;
            if (sepIdx != -1) {
                String key = line.substring(0, sepIdx).trim().toLowerCase(Locale.ROOT);
                String val = line.substring(sepIdx + 1).trim();
                if (key.equals("signal") || key.equals("s")) {
                    if (!val.isEmpty() && isValidDigit(val.charAt(0))) {
                        foundSignal = val.charAt(0);
                        hasKeyValue = true;
                    }
                } else if (key.equals("duration") || key.equals("d")) {
                    try {
                        foundDuration = Integer.parseInt(val);
                        hasKeyValue = true;
                    } catch (NumberFormatException ignored) {}
                } else if (key.equals("volume") || key.equals("v")) {
                    try {
                        foundVolume = Integer.parseInt(val);
                        hasKeyValue = true;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (foundSignal != null) {
            int dur = foundDuration != null ? foundDuration : DEFAULT_DURATION_MS;
            int vol = foundVolume != null ? foundVolume : DEFAULT_VOLUME;
            return Optional.of(new DtmfSignal(foundSignal, dur, vol));
        }

        // If body contained other key-values but no valid signal, do not treat as raw digit
        if (hasKeyValue) {
            return Optional.empty();
        }

        // 2. If single character
        if (trimmed.length() == 1) {
            char c = trimmed.charAt(0);
            if (isValidDigit(c)) {
                if (ct.contains("dtmf") || !Character.isLetter(c) || (Character.toUpperCase(c) >= 'A' && Character.toUpperCase(c) <= 'D')) {
                    return Optional.of(new DtmfSignal(c));
                }
            }
        }

        // 3. Check for "dtmf: 5" or "DTMF 5"
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("dtmf")) {
            String remainder = trimmed.substring(4).replaceAll("^[\\s:=]+", "").trim();
            if (!remainder.isEmpty() && isValidDigit(remainder.charAt(0))) {
                return Optional.of(new DtmfSignal(remainder.charAt(0)));
            }
        }

        return Optional.empty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DtmfSignal that = (DtmfSignal) o;
        return digit == that.digit && duration == that.duration && volume == that.volume;
    }

    @Override
    public int hashCode() {
        return Objects.hash(digit, duration, volume);
    }

    @Override
    public String toString() {
        return "DtmfSignal[digit=" + digit + ", duration=" + duration + "ms, volume=" + volume + "]";
    }
}
