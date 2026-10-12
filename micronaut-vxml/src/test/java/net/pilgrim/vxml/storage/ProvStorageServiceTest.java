package net.pilgrim.vxml.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProvStorageServiceTest {

    @TempDir
    Path tempStorageDir;

    private ProvStorageConfiguration config;
    private ProvStorageService storageService;

    @BeforeEach
    void setUp() {
        config = new ProvStorageConfiguration();
        config.setSafeRootPath(tempStorageDir.toString());
        config.setSafeRootPrefix("media/");
        config.setAllowFileProtocol(false);
        storageService = new ProvStorageService(null, config);
    }

    @Test
    void testIsProvUri() {
        assertTrue(storageService.isProvUri("prov://prompts/welcome.wav"));
        assertTrue(storageService.isProvUri("prov:///prompts/welcome.wav"));
        assertTrue(storageService.isProvUri("prov:prompts/welcome.wav"));
        assertTrue(storageService.isProvUri("PROV://PROMPTS/WELCOME.WAV"));

        assertFalse(storageService.isProvUri("file:///tmp/audio.wav"));
        assertFalse(storageService.isProvUri("http://example.com/audio.wav"));
        assertFalse(storageService.isProvUri("tone:440,500"));
        assertFalse(storageService.isProvUri((String) null));
        assertFalse(storageService.isProvUri((java.net.URI) null));
        assertFalse(storageService.isProvUri(""));
    }

    @Test
    void testExtractRelativePathValid() {
        assertEquals("prompts/welcome.wav", storageService.extractRelativePath("prov://prompts/welcome.wav"));
        assertEquals("prompts/welcome.wav", storageService.extractRelativePath("prov:///prompts/welcome.wav"));
        assertEquals("prompts/welcome.wav", storageService.extractRelativePath("prov:prompts/welcome.wav"));
        assertEquals("user/101/greeting.wav", storageService.extractRelativePath("prov://user/101/greeting.wav"));
    }

    @Test
    void testPathTraversalBlocked() {
        // Direct parent traversal
        assertThrows(SecurityException.class, () -> storageService.extractRelativePath("prov://../etc/passwd"));
        assertThrows(SecurityException.class, () -> storageService.extractRelativePath("prov:///../../etc/shadow"));
        assertThrows(SecurityException.class, () -> storageService.extractRelativePath("prov://prompts/../../etc/passwd"));
        assertThrows(SecurityException.class, () -> storageService.extractRelativePath("prov://prompts/../secret.wav"));

        // URL encoded traversal
        assertThrows(SecurityException.class, () -> storageService.extractRelativePath("prov://%2e%2e/etc/passwd"));
        assertThrows(SecurityException.class, () -> storageService.extractRelativePath("prov://%2e%2e%2f%2e%2e%2fetc%2fshadow"));

        // Null byte injection
        assertThrows(SecurityException.class, () -> storageService.extractRelativePath("prov://prompts/welcome.wav\0.png"));
    }

    @Test
    void testFileProtocolMitigation() {
        // By default, allowFileProtocol is false to prevent exfiltration
        assertFalse(storageService.isFileAllowed("file:///etc/passwd"));
        assertFalse(storageService.isFileAllowed("file:///home/user/.ssh/id_rsa"));
        assertFalse(storageService.isFileAllowed("/etc/shadow"));

        // When explicitly allowed, only paths strictly inside safeRootDirectory are permitted
        config.setAllowFileProtocol(true);
        assertFalse(storageService.isFileAllowed("file:///etc/passwd"), "Arbitrary host paths must remain blocked");

        Path safeFile = tempStorageDir.resolve("safe_audio.wav");
        assertTrue(storageService.isFileAllowed("file://" + safeFile.toString()), "File inside safe root must be permitted");
    }

    @Test
    void testStoreAndLoadAudioInSafeRoot() {
        byte[] expectedAudio = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        String storedKey = storageService.storeAudio("prov://prompts/test.wav", expectedAudio);

        assertEquals("media/prompts/test.wav", storedKey);

        byte[] loadedAudio = storageService.loadAudio("prov://prompts/test.wav");
        assertArrayEquals(expectedAudio, loadedAudio);

        // Verify the file was saved within safe root
        Path fileOnDisk = tempStorageDir.resolve("prompts/test.wav");
        assertTrue(Files.exists(fileOnDisk));
    }

    @Test
    void testLoadNonExistentAssetReturnsEmpty() {
        byte[] audio = storageService.loadAudio("prov://missing/file.wav");
        assertNotNull(audio);
        assertEquals(0, audio.length);
    }
}
