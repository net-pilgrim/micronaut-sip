package net.pilgrim.msml.model.moml;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Sealed interface representing elements in Media Objects Markup Language (MOML) per RFC 5707 Section 4.
 */
public sealed interface MomlElement permits
        MomlElement.Play,
        MomlElement.Record,
        MomlElement.Dtmf,
        MomlElement.Var,
        MomlElement.Exit,
        MomlElement.Sleep {

    /**
     * MOML &lt;play&gt; element for streaming audio prompts or tones to a connection.
     */
    record Play(
            String id,
            Duration offset,
            Duration skip,
            boolean barge,
            boolean clearDb,
            List<AudioSource> sources
    ) implements MomlElement {
        public Play {
            sources = sources != null ? List.copyOf(sources) : List.of();
            if (offset == null) offset = Duration.ZERO;
            if (skip == null) skip = Duration.ZERO;
        }

        public static Play of(AudioSource source) {
            return new Play(null, Duration.ZERO, Duration.ZERO, true, true, List.of(source));
        }

        public static Play of(String audioUri) {
            return of(AudioSource.of(audioUri));
        }

        public static Play ofSources(List<AudioSource> sources) {
            return new Play(null, Duration.ZERO, Duration.ZERO, true, true, sources);
        }
    }

    /**
     * MOML &lt;record&gt; element for capturing inbound caller audio stream.
     */
    record Record(
            String id,
            URI dest,
            String format,
            Duration maxTime,
            String termChar,
            boolean beep,
            boolean append
    ) implements MomlElement {
        public Record {
            if (format == null || format.isBlank()) format = "audio/wav";
            if (maxTime == null || maxTime.isZero() || maxTime.isNegative()) maxTime = Duration.ofSeconds(30);
            if (termChar == null) termChar = "#";
        }

        public static Record of(URI dest) {
            return new Record(null, dest, "audio/wav", Duration.ofSeconds(30), "#", true, false);
        }

        public static Record of(String destUri, Duration maxTime, String termChar) {
            return new Record(null, URI.create(destUri), "audio/wav", maxTime, termChar, true, false);
        }
    }

    /**
     * MOML &lt;dtmf&gt; element for collecting touch-tone keypad input.
     */
    record Dtmf(
            String id,
            boolean clearDb,
            Duration fdt,
            Duration idt,
            Duration edt,
            DtmfPattern pattern
    ) implements MomlElement {
        public Dtmf {
            if (fdt == null) fdt = Duration.ofSeconds(5);
            if (idt == null) idt = Duration.ofSeconds(3);
            if (edt == null) edt = Duration.ofSeconds(2);
            if (pattern == null) pattern = DtmfPattern.ofSingleDigit();
        }

        public static Dtmf ofPattern(DtmfPattern pattern) {
            return new Dtmf(null, true, Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(2), pattern);
        }
    }

    /**
     * MOML &lt;var&gt; element defining a dialog variable.
     */
    record Var(String name, String value) implements MomlElement {}

    /**
     * MOML &lt;exit&gt; element indicating dialog termination and return values.
     */
    record Exit(String nameList) implements MomlElement {
        public static Exit now() {
            return new Exit(null);
        }
    }

    /**
     * MOML &lt;sleep&gt; element pausing media execution.
     */
    record Sleep(Duration duration) implements MomlElement {
        public Sleep {
            if (duration == null || duration.isNegative()) duration = Duration.ZERO;
        }

        public static Sleep of(Duration duration) {
            return new Sleep(duration);
        }
    }
}
