package net.pilgrim.netann.service;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and decodes announcement audio prompts from various URL schemes
 * (classpath:, file:, http:, https:, /provisioned/, or builtin tones)
 * into standard 8000 Hz, 16-bit mono, signed PCM-16LE samples for RTP streaming.
 */
@Singleton
public class AnnouncementAudioLoader {

    private static final Logger LOG = LoggerFactory.getLogger(AnnouncementAudioLoader.class);

    private static final AudioFormat TARGET_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            8000.0f, // 8 kHz
            16,      // 16-bit
            1,       // mono
            2,       // 2 bytes per frame
            8000.0f,
            false    // little-endian
    );

    private final Map<String, byte[]> provisionedPrompts = new ConcurrentHashMap<>();

    public AnnouncementAudioLoader() {
        // Register default built-in tones
        provisionedPrompts.put("welcome", generateTone(440, 1000));
        provisionedPrompts.put("busy", generateTone(480, 500));
    }

    public void registerProvisioned(String id, byte[] pcm16LeAudio) {
        if (id != null && pcm16LeAudio != null) {
            provisionedPrompts.put(id.trim().toLowerCase(), pcm16LeAudio);
        }
    }

    /**
     * Loads and decodes an announcement prompt into 8000 Hz 16-bit mono signed PCM-16LE audio.
     *
     * @param playUri URI specified in the RFC 4240 play= parameter
     * @return raw PCM-16LE audio bytes
     * @throws FileNotFoundException if the prompt cannot be found
     * @throws IOException           if retrieval or audio decoding fails
     */
    public byte[] loadAudio(String playUri) throws FileNotFoundException, IOException {
        if (playUri == null || playUri.isBlank()) {
            throw new FileNotFoundException("Empty play URI");
        }

        String uri = playUri.trim();

        // 1. Built-in synthetic tones (useful for tests and synthetic prompts)
        if (uri.startsWith("builtin:") || uri.startsWith("tone:")) {
            String spec = uri.substring(uri.indexOf(':') + 1);
            int freq = 440;
            int durMs = 1000;
            if (!spec.isBlank()) {
                String[] parts = spec.split("[,;]");
                try {
                    freq = Integer.parseInt(parts[0].trim());
                    if (parts.length > 1) {
                        durMs = Integer.parseInt(parts[1].trim());
                    }
                } catch (NumberFormatException ignored) {}
            }
            return generateTone(freq, durMs);
        }

        // 2. Provisioned announcement sequence (/provisioned/<id> per RFC 4240 §3.3)
        if (uri.startsWith("/provisioned/") || uri.startsWith("provisioned:")) {
            String id = uri.startsWith("/provisioned/") ? uri.substring(13) : uri.substring(12);
            id = id.trim().toLowerCase();
            byte[] cached = provisionedPrompts.get(id);
            if (cached != null) {
                return cached;
            }
            // Fallback to classpath resource prompts/<id>.wav
            InputStream cpIn = getClass().getResourceAsStream("/prompts/" + id + ".wav");
            if (cpIn != null) {
                try (cpIn) {
                    return decodeWavOrPcm(cpIn.readAllBytes());
                }
            }
            throw new FileNotFoundException("Provisioned announcement not found: " + id);
        }

        // 3. Classpath resource (classpath:... or resource:... or /prompts/...)
        String resourcePath = null;
        if (uri.startsWith("classpath:")) {
            resourcePath = uri.substring(10);
        } else if (uri.startsWith("resource:")) {
            resourcePath = uri.substring(9);
        } else if (uri.startsWith("/prompts/") || uri.startsWith("prompts/")) {
            resourcePath = uri.startsWith("/") ? uri : "/" + uri;
        }

        if (resourcePath != null) {
            if (!resourcePath.startsWith("/")) {
                resourcePath = "/" + resourcePath;
            }
            InputStream in = getClass().getResourceAsStream(resourcePath);
            if (in == null) {
                throw new FileNotFoundException("Classpath announcement resource not found: " + resourcePath);
            }
            try (in) {
                return decodeWavOrPcm(in.readAllBytes());
            }
        }

        // 4. File URL or direct local filesystem path
        if (uri.startsWith("file://") || uri.startsWith("/") || (uri.length() > 2 && uri.charAt(1) == ':')) {
            String pathStr = uri.startsWith("file://") ? uri.substring(7) : uri;
            Path path = Path.of(pathStr);
            if (!Files.exists(path)) {
                throw new FileNotFoundException("Announcement file not found: " + pathStr);
            }
            return decodeWavOrPcm(Files.readAllBytes(path));
        }

        // 5. HTTP / HTTPS URL
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            try {
                URL url = URI.create(uri).toURL();
                try (InputStream in = url.openStream()) {
                    return decodeWavOrPcm(in.readAllBytes());
                }
            } catch (FileNotFoundException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed retrieving HTTP announcement from " + uri + ": " + e.getMessage(), e);
            }
        }

        // 6. Check provisioned prompt by raw name or try loading as classpath resource
        byte[] cached = provisionedPrompts.get(uri.toLowerCase());
        if (cached != null) {
            return cached;
        }
        InputStream directIn = getClass().getResourceAsStream("/" + uri);
        if (directIn != null) {
            try (directIn) {
                return decodeWavOrPcm(directIn.readAllBytes());
            }
        }

        throw new FileNotFoundException("Announcement resource not found: " + uri);
    }

    /**
     * Decodes WAV data into 8000 Hz 16-bit mono signed PCM-16LE samples.
     * If the audio is not in WAV container format, treats it as raw PCM-16LE.
     */
    public byte[] decodeWavOrPcm(byte[] audioBytes) throws IOException {
        if (audioBytes == null || audioBytes.length == 0) {
            return new byte[0];
        }

        // Check for RIFF WAV header
        if (audioBytes.length >= 12
                && audioBytes[0] == 'R' && audioBytes[1] == 'I' && audioBytes[2] == 'F' && audioBytes[3] == 'F'
                && audioBytes[8] == 'W' && audioBytes[9] == 'A' && audioBytes[10] == 'V' && audioBytes[11] == 'E') {
            try (AudioInputStream sourceStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audioBytes))) {
                AudioFormat sourceFormat = sourceStream.getFormat();
                if (isMatchingFormat(sourceFormat, TARGET_FORMAT)) {
                    return sourceStream.readAllBytes();
                }

                // Convert to 8kHz 16-bit mono PCM if possible
                try (AudioInputStream targetStream = AudioSystem.getAudioInputStream(TARGET_FORMAT, sourceStream)) {
                    return targetStream.readAllBytes();
                }
            } catch (Exception e) {
                LOG.warn("Failed decoding WAV with AudioSystem, attempting raw PCM extraction: {}", e.getMessage());
                // Fallback: Skip 44-byte standard WAV header if present
                if (audioBytes.length > 44) {
                    byte[] pcm = new byte[audioBytes.length - 44];
                    System.arraycopy(audioBytes, 44, pcm, 0, pcm.length);
                    return pcm;
                }
            }
        }

        return audioBytes;
    }

    private boolean isMatchingFormat(AudioFormat f1, AudioFormat f2) {
        return Math.abs(f1.getSampleRate() - f2.getSampleRate()) < 1.0f
                && f1.getSampleSizeInBits() == f2.getSampleSizeInBits()
                && f1.getChannels() == f2.getChannels()
                && f1.getEncoding() == f2.getEncoding()
                && f1.isBigEndian() == f2.isBigEndian();
    }

    /**
     * Generates a 16-bit mono PCM-16LE sine wave at 8000 Hz.
     */
    public static byte[] generateTone(int frequencyHz, int durationMs) {
        int sampleRate = 8000;
        int numSamples = (sampleRate * durationMs) / 1000;
        ByteBuffer buffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < numSamples; i++) {
            double angle = 2.0 * Math.PI * i * frequencyHz / sampleRate;
            short sample = (short) (Math.sin(angle) * 16000);
            buffer.putShort(sample);
        }
        return buffer.array();
    }

    /**
     * Creates a valid WAV byte array containing an 8000 Hz 16-bit mono sine wave.
     */
    public static byte[] createWav(int frequencyHz, int durationMs) {
        byte[] pcm = generateTone(frequencyHz, durationMs);
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(36 + pcm.length);
        wav.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(16);                 // subchunk1size (16 for PCM)
        wav.putShort((short) 1);         // audio format (1 = PCM)
        wav.putShort((short) 1);         // channels (1 = mono)
        wav.putInt(8000);                // sample rate
        wav.putInt(8000 * 2);            // byte rate
        wav.putShort((short) 2);         // block align
        wav.putShort((short) 16);        // bits per sample
        wav.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(pcm.length);
        wav.put(pcm);
        return wav.array();
    }
}
