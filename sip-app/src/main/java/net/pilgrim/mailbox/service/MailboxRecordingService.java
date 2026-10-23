package net.pilgrim.mailbox.service;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.objectstorage.ObjectStorageOperations;
import io.micronaut.objectstorage.request.UploadRequest;
import io.micronaut.objectstorage.response.UploadResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.mailbox.config.MailboxConfiguration;
import net.pilgrim.mailbox.model.VoicemailMessage;
import net.pilgrim.sip.model.SipUri;
import net.pilgrim.sip.rtp.media.AudioRecording;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Service managing voicemail message storage in Micronaut Object Storage.
 * Saves recorded audio as standard RIFF WAVE files at caller/tstamp.
 */
@Singleton
public class MailboxRecordingService {

    private static final Logger LOG = LoggerFactory.getLogger(MailboxRecordingService.class);

    private final ObjectStorageOperations<?, ?, ?> objectStorage;
    private final MailboxConfiguration config;
    private final Object storageLock = new Object();

    @Inject
    public MailboxRecordingService(@Nullable ObjectStorageOperations<?, ?, ?> objectStorage,
                                   MailboxConfiguration config) {
        this.objectStorage = objectStorage;
        this.config = config != null ? config : new MailboxConfiguration();
    }

    /**
     * Stores a recorded voicemail message into object storage.
     * When mailboxOwner is provided (e.g. from alie+mailbox), it is stored at:
     *   <mailboxOwner>/<caller>/<tstamp>.wav
     * When mailboxOwner is omitted (generic mailbox call), it is stored at:
     *   <caller>/<tstamp>.wav
     *
     * @param callId       SIP Call-ID
     * @param mailboxOwner target mailbox owner (e.g. "alie"), or null for default
     * @param caller       caller identity (e.g. from From header)
     * @param recording    recorded audio frames
     * @return storage key, or null if audio was empty
     */
    public String storeMessage(String callId, @Nullable String mailboxOwner, String caller, AudioRecording recording) {
        if (recording == null || recording.getPcmData().length == 0) {
            LOG.warn("No audio data to store for Call-ID: {}", callId);
            return null;
        }

        long durationMs = recording.getDurationMs();
        String cleanCaller = extractCaller(caller);
        long tstamp = System.currentTimeMillis();

        String prefix = config.getStoragePrefix() != null ? config.getStoragePrefix().trim() : "";
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }

        String key;
        if (mailboxOwner != null && !mailboxOwner.isBlank()) {
            String cleanOwner = sanitizeKeyComponent(mailboxOwner.trim());
            key = prefix + cleanOwner + "/" + cleanCaller + "/" + tstamp + ".wav";
        } else {
            key = prefix + cleanCaller + "/" + tstamp + ".wav";
        }

        LOG.info("Storing recorded voicemail message for Call-ID: {}, owner: {}, caller: {} at key: {}, duration: {}ms",
                callId, mailboxOwner, cleanCaller, key, durationMs);

        byte[] wavBytes = recording.toWavBytes();

        if (objectStorage != null) {
            int retries = 3;
            while (true) {
                try {
                    UploadRequest uploadRequest = UploadRequest.fromBytes(wavBytes, key, "audio/wav");
                    synchronized (storageLock) {
                        UploadResponse<?> response = objectStorage.upload(uploadRequest);
                    }
                    LOG.info("Voicemail successfully uploaded to object storage with key: {} ({} bytes)", key, wavBytes.length);
                    return key;
                } catch (Exception e) {
                    if (--retries > 0) {
                        LOG.warn("Retrying upload for key {} after error: {}", key, e.getMessage());
                        try {
                            Thread.sleep(30 + (long) (Math.random() * 30));
                        } catch (InterruptedException ignored) {}
                    } else {
                        LOG.error("Failed uploading voicemail to object storage with key: {}", key, e);
                        throw new RuntimeException("Object storage upload failed for key: " + key, e);
                    }
                }
            }
        } else {
            LOG.warn("ObjectStorageOperations bean not present, simulated storage of key: {}", key);
            return key;
        }
    }

    public String storeMessage(String callId, String caller, AudioRecording recording) {
        return storeMessage(callId, null, caller, recording);
    }

    /**
     * Stores a custom greeting for a mailbox owner into object storage at:
     *   <mailboxOwner>/greeting.wav
     */
    public String storeGreeting(String mailboxOwner, AudioRecording recording) {
        if (mailboxOwner == null || mailboxOwner.isBlank() || recording == null || recording.getPcmData().length == 0) {
            LOG.warn("Cannot store greeting: invalid owner or empty recording");
            return null;
        }

        String prefix = config.getStoragePrefix() != null ? config.getStoragePrefix().trim() : "";
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }

        String cleanOwner = sanitizeKeyComponent(mailboxOwner.trim());
        String key = prefix + cleanOwner + "/greeting.wav";

        LOG.info("Storing custom greeting for owner: {} at key: {}, duration: {}ms",
                cleanOwner, key, recording.getDurationMs());

        byte[] wavBytes = recording.toWavBytes();

        if (objectStorage != null) {
            int retries = 3;
            while (true) {
                try {
                    UploadRequest uploadRequest = UploadRequest.fromBytes(wavBytes, key, "audio/wav");
                    synchronized (storageLock) {
                        objectStorage.upload(uploadRequest);
                    }
                    LOG.info("Custom greeting uploaded to object storage with key: {} ({} bytes)", key, wavBytes.length);
                    return key;
                } catch (Exception e) {
                    if (--retries > 0) {
                        LOG.warn("Retrying greeting upload for key {} after error: {}", key, e.getMessage());
                        try {
                            Thread.sleep(30 + (long) (Math.random() * 30));
                        } catch (InterruptedException ignored) {}
                    } else {
                        LOG.error("Failed uploading greeting to object storage with key: {}", key, e);
                        throw new RuntimeException("Object storage upload failed for key: " + key, e);
                    }
                }
            }
        } else {
            LOG.warn("ObjectStorageOperations bean not present, simulated greeting storage of key: {}", key);
            return key;
        }
    }

    /**
     * Retrieves the custom greeting audio for a mailbox owner, if configured.
     */
    public Optional<byte[]> retrieveGreeting(String mailboxOwner) {
        if (mailboxOwner == null || mailboxOwner.isBlank()) {
            return Optional.empty();
        }
        String prefix = config.getStoragePrefix() != null ? config.getStoragePrefix().trim() : "";
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        String cleanOwner = sanitizeKeyComponent(mailboxOwner.trim());
        return retrieveMessage(prefix + cleanOwner + "/greeting.wav");
    }

    /**
     * Checks whether a custom greeting exists for a mailbox owner.
     */
    public boolean hasCustomGreeting(String mailboxOwner) {
        return retrieveGreeting(mailboxOwner).isPresent();
    }

    /**
     * Deletes the custom greeting for a mailbox owner (reverting to system default).
     */
    public boolean deleteGreeting(String mailboxOwner) {
        if (mailboxOwner == null || mailboxOwner.isBlank()) {
            return false;
        }
        String prefix = config.getStoragePrefix() != null ? config.getStoragePrefix().trim() : "";
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        String cleanOwner = sanitizeKeyComponent(mailboxOwner.trim());
        return deleteMessage(prefix + cleanOwner + "/greeting.wav");
    }

    /**
     * Lists all voicemail messages for a specific mailbox owner, sorted by timestamp ascending.
     */
    public List<VoicemailMessage> listMessagesForOwner(String mailboxOwner) {
        if (mailboxOwner == null || mailboxOwner.isBlank()) {
            return Collections.emptyList();
        }
        String cleanOwner = sanitizeKeyComponent(mailboxOwner.trim());
        String ownerPrefix = cleanOwner + "/";
        String prefix = config.getStoragePrefix() != null ? config.getStoragePrefix().trim() : "";
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            ownerPrefix = prefix + ownerPrefix;
        }

        Set<String> allKeys = listMessages();
        List<VoicemailMessage> messages = new ArrayList<>();

        for (String key : allKeys) {
            if (key.startsWith(ownerPrefix) && !key.endsWith("greeting.wav") && key.endsWith(".wav")) {
                String sub = key.substring(ownerPrefix.length());
                String[] parts = sub.split("/");
                if (parts.length == 2) {
                    String caller = parts[0];
                    String tstampStr = parts[1].substring(0, parts[1].length() - 4);
                    try {
                        long tstamp = Long.parseLong(tstampStr);
                        messages.add(new VoicemailMessage(key, cleanOwner, caller, tstamp, 0));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        messages.sort(Comparator.comparingLong(VoicemailMessage::timestamp));
        return messages;
    }

    /**
     * Gets the count of messages currently stored for an owner.
     */
    public int getOwnerMessageCount(String mailboxOwner) {
        return listMessagesForOwner(mailboxOwner).size();
    }

    /**
     * Extracts a normalized caller identifier from a SIP From header or URI.
     */
    public String extractCaller(String callerHeader) {
        if (callerHeader == null || callerHeader.isBlank()) {
            return "anonymous";
        }
        try {
            SipUri uri = SipUri.extractUri(callerHeader);
            if (uri != null && uri.getUser() != null && !uri.getUser().isBlank()) {
                return sanitizeKeyComponent(uri.getUser());
            }
        } catch (Exception ignored) {}

        String cleaned = callerHeader.replaceAll("^<|>$", "").split(";")[0].trim();
        if (cleaned.startsWith("sip:") || cleaned.startsWith("sips:")) {
            cleaned = cleaned.substring(cleaned.indexOf(':') + 1);
            int atIdx = cleaned.indexOf('@');
            if (atIdx != -1) {
                cleaned = cleaned.substring(0, atIdx);
            }
        }
        cleaned = sanitizeKeyComponent(cleaned);
        return cleaned.isBlank() ? "anonymous" : cleaned;
    }

    private String sanitizeKeyComponent(String input) {
        if (input == null) return "anonymous";
        String sanitized = input.replaceAll("[^a-zA-Z0-9+._-]", "_");
        return sanitized.isBlank() ? "anonymous" : sanitized;
    }

    /**
     * Retrieves the stored audio bytes for a given key.
     */
    public Optional<byte[]> retrieveMessage(String key) {
        if (objectStorage == null || key == null) {
            return Optional.empty();
        }
        var entryOpt = objectStorage.retrieve(key);
        if (entryOpt.isEmpty() && key.endsWith(".wav")) {
            entryOpt = objectStorage.retrieve(key.substring(0, key.length() - 4));
        } else if (entryOpt.isEmpty()) {
            entryOpt = objectStorage.retrieve(key + ".wav");
        }
        return entryOpt.map(entry -> {
            try (InputStream in = entry.getInputStream()) {
                return in.readAllBytes();
            } catch (Exception e) {
                LOG.error("Failed reading object storage entry: {}", key, e);
                return null;
            }
        });
    }

    /**
     * Lists all voicemail message keys currently in storage.
     */
    public Set<String> listMessages() {
        if (objectStorage == null) {
            return Collections.emptySet();
        }
        try {
            return objectStorage.listObjects();
        } catch (Exception e) {
            LOG.debug("Error listing object storage entries: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Deletes a message from object storage.
     */
    public boolean deleteMessage(String key) {
        if (objectStorage == null || key == null) {
            return false;
        }
        try {
            objectStorage.delete(key);
            if (key.endsWith(".wav")) {
                objectStorage.delete(key.substring(0, key.length() - 4));
            } else {
                objectStorage.delete(key + ".wav");
            }
            return true;
        } catch (Exception e) {
            LOG.error("Failed deleting voicemail from object storage with key: {}", key, e);
            return false;
        }
    }

    public ObjectStorageOperations<?, ?, ?> getObjectStorage() {
        return objectStorage;
    }
}
