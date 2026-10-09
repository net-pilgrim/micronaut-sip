package net.pilgrim.mailbox;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import net.pilgrim.mailbox.service.MailboxRecordingService;
import net.pilgrim.sip.rtp.media.AudioRecording;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = "test")
class MailboxRecordingServiceTest {

    @Inject
    MailboxRecordingService recordingService;

    @Test
    void testStoreAndRetrieveVoicemailInObjectStorage() {
        assertNotNull(recordingService);
        assertNotNull(recordingService.getObjectStorage(), "ObjectStorageOperations should be injected");

        byte[] pcm = new byte[8000 * 2]; // 1 second of 8kHz 16-bit mono
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i & 0x7F);
        }

        AudioRecording recording = new AudioRecording("call-msg-123", pcm, 8000, 1, 1000);
        String key = recordingService.storeMessage("call-msg-123", "sip:caller@example.com", recording);

        assertNotNull(key);
        assertTrue(key.startsWith("caller/"), "Key should start with caller/");
        assertTrue(key.endsWith(".wav"), "Key should end with .wav");
        String[] parts = key.split("/");
        assertEquals(2, parts.length, "Key should be caller/tstamp.wav");
        assertEquals("caller", parts[0]);
        String tstampPart = parts[1].substring(0, parts[1].length() - 4);
        assertDoesNotThrow(() -> Long.parseLong(tstampPart), "Timestamp part must be numeric");

        // Retrieve from object storage
        Optional<byte[]> retrieved = recordingService.retrieveMessage(key);
        assertTrue(retrieved.isPresent(), "Retrieved WAV bytes should be present");
        byte[] expectedWav = recording.toWavBytes();
        assertArrayEquals(expectedWav, retrieved.get(), "Retrieved audio should match stored WAV bytes");

        // Verify WAV header
        byte[] storedBytes = retrieved.get();
        assertEquals('R', (char) storedBytes[0]);
        assertEquals('I', (char) storedBytes[1]);
        assertEquals('F', (char) storedBytes[2]);
        assertEquals('F', (char) storedBytes[3]);

        // List messages
        Set<String> list = recordingService.listMessages();
        assertTrue(list.contains(key), "Object storage listing should contain uploaded key");

        // Delete message
        boolean deleted = recordingService.deleteMessage(key);
        assertTrue(deleted);
        assertFalse(recordingService.retrieveMessage(key).isPresent());
    }

    @Test
    void testStoreAndRetrieveWithMailboxOwner() {
        byte[] pcm = new byte[8000 * 2];
        AudioRecording recording = new AudioRecording("call-owner-123", pcm, 8000, 1, 1000);
        String key = recordingService.storeMessage("call-owner-123", "alie", "sip:bob@example.com", recording);

        assertNotNull(key);
        assertTrue(key.startsWith("alie/bob/"), "Key should start with alie/bob/");
        assertTrue(key.endsWith(".wav"), "Key should end with .wav");
        String[] parts = key.split("/");
        assertEquals(3, parts.length, "Key should be alie/bob/tstamp.wav");
        assertEquals("alie", parts[0]);
        assertEquals("bob", parts[1]);
        String tstampPart = parts[2].substring(0, parts[2].length() - 4);
        assertDoesNotThrow(() -> Long.parseLong(tstampPart), "Timestamp part must be numeric");

        Optional<byte[]> retrieved = recordingService.retrieveMessage(key);
        assertTrue(retrieved.isPresent());
        recordingService.deleteMessage(key);
    }

    @Test
    void testCallerExtraction() {
        assertEquals("alice", recordingService.extractCaller("<sip:alice@127.0.0.1>;tag=caller-tag-123"));
        assertEquals("bob", recordingService.extractCaller("sip:bob@example.com"));
        assertEquals("1001", recordingService.extractCaller("<sip:1001@pbx.local>"));
        assertEquals("direct_caller", recordingService.extractCaller("direct_caller"));
        assertEquals("anonymous", recordingService.extractCaller(null));
        assertEquals("anonymous", recordingService.extractCaller("   "));
    }

    @Test
    void testEmptyRecordingReturnsNull() {
        AudioRecording empty = new AudioRecording("call-empty", new byte[0], 8000, 1, 0);
        String key = recordingService.storeMessage("call-empty", "caller", empty);
        assertNull(key);
    }
}
