package net.pilgrim.mailbox.model;

import io.micronaut.core.annotation.Introspected;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Representation of a stored voicemail message.
 */
@Introspected
public record VoicemailMessage(
        String key,
        String mailboxOwner,
        String caller,
        long timestamp,
        long durationMs
) {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public String formattedDate() {
        return FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    public String audioUri() {
        return "prov://" + key;
    }
}
