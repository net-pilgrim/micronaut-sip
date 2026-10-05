package net.pilgrim.netann.service;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.netann.config.NetannConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and decodes announcement audio prompts from various URL schemes
 * (classpath:, file:, http:, https:, /provisioned/, or builtin tones)
 * into standard 8000 Hz, 16-bit mono, signed PCM-16LE samples for RTP streaming.
 * Includes security controls against SSRF, memory exhaustion, and path traversal.
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

    private final NetannConfiguration config;
    private final Map<String, byte[]> provisionedPrompts = new ConcurrentHashMap<>();

    public AnnouncementAudioLoader() {
        this(new NetannConfiguration());
    }

    @Inject
    public AnnouncementAudioLoader(@Nullable NetannConfiguration config) {
        this.config = config != null ? config : new NetannConfiguration();
        // Register default built-in tones
        provisionedPrompts.put("welcome", generateTone(440, 1000));
        provisionedPrompts.put("busy", generateTone(480, 500));
    }

    public NetannConfiguration getConfiguration() {
        return config;
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
     * @throws SecurityException     if the prompt URI violates SSRF, jail, or permission policies
     * @throws IOException           if retrieval, size cap, or audio decoding fails
     */
    public byte[] loadAudio(String playUri) throws FileNotFoundException, IOException {
        if (playUri == null || playUri.isBlank()) {
            throw new FileNotFoundException("Empty play URI");
        }

        String uri = playUri.trim();

        // 1. Built-in synthetic tones (useful for tests and synthetic prompts)
        if (uri.startsWith("builtin:") || uri.startsWith("tone:")) {
            String spec = uri.substring(uri.lastIndexOf(':') + 1);
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
                    return decodeWavOrPcm(readBoundedStream(cpIn, config.getMaxAudioSizeBytes()));
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
                return decodeWavOrPcm(readBoundedStream(in, config.getMaxAudioSizeBytes()));
            }
        }

        // 4. File URL or direct local filesystem path
        if (uri.startsWith("file://") || uri.startsWith("/") || (uri.length() > 2 && uri.charAt(1) == ':')) {
            String pathStr = uri.startsWith("file://") ? uri.substring(7) : uri;
            return loadFileAudio(pathStr);
        }

        // 5. HTTP / HTTPS URL
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            return loadHttpAudio(uri);
        }

        // 6. Check provisioned prompt by raw name or try loading as classpath resource
        byte[] cached = provisionedPrompts.get(uri.toLowerCase());
        if (cached != null) {
            return cached;
        }
        InputStream directIn = getClass().getResourceAsStream("/" + uri);
        if (directIn != null) {
            try (directIn) {
                return decodeWavOrPcm(readBoundedStream(directIn, config.getMaxAudioSizeBytes()));
            }
        }

        throw new FileNotFoundException("Announcement resource not found: " + uri);
    }

    private byte[] loadHttpAudio(String uriStr) throws FileNotFoundException, IOException {
        URI uri = URI.create(uriStr);
        validateHttpUrl(uri);

        URL url = uri.toURL();
        URLConnection rawConn = url.openConnection();
        if (!(rawConn instanceof HttpURLConnection httpConn)) {
            throw new SecurityException("Unsupported connection protocol for " + uriStr);
        }

        httpConn.setConnectTimeout(config.getHttpConnectTimeoutMs());
        httpConn.setReadTimeout(config.getHttpReadTimeoutMs());
        httpConn.setInstanceFollowRedirects(false);
        httpConn.setRequestProperty("User-Agent", "Micronaut-NetAnn/1.0");

        int status = httpConn.getResponseCode();
        if (status == HttpURLConnection.HTTP_NOT_FOUND) {
            throw new FileNotFoundException("Remote audio prompt not found: " + uriStr);
        }
        if (status < 200 || status >= 300) {
            throw new IOException("Remote audio server returned HTTP " + status + " for " + uriStr);
        }

        long contentLength = httpConn.getContentLengthLong();
        if (contentLength > config.getMaxAudioSizeBytes()) {
            throw new IOException("Remote audio prompt size (" + contentLength + " bytes) exceeds maximum configured limit ("
                    + config.getMaxAudioSizeBytes() + " bytes)");
        }

        try (InputStream in = httpConn.getInputStream()) {
            byte[] data = readBoundedStream(in, config.getMaxAudioSizeBytes());
            return decodeWavOrPcm(data);
        }
    }

    private void validateHttpUrl(URI uri) throws SecurityException {
        if (!config.isHttpEnabled()) {
            throw new SecurityException("HTTP audio prompt fetching is disabled by configuration");
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new SecurityException("Unsupported audio URI scheme: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("Missing host in HTTP audio URL: " + uri);
        }

        // Host allowlist check (if configured)
        if (!config.getAllowedHosts().isEmpty()) {
            boolean allowed = false;
            String lowerHost = host.toLowerCase(Locale.ROOT);
            for (String allowedHost : config.getAllowedHosts()) {
                String lowerAllowed = allowedHost.trim().toLowerCase(Locale.ROOT);
                if (lowerHost.equals(lowerAllowed) || lowerHost.endsWith("." + lowerAllowed)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new SecurityException("Host '" + host + "' is not permitted by allowed-hosts configuration");
            }
        }

        // SSRF protection: DNS resolution and private/loopback/metadata IP blocking
        if (config.isSsrfProtectionEnabled()) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress addr : addresses) {
                    if (isRestrictedIp(addr)) {
                        throw new SecurityException("SSRF blocked: host '" + host + "' resolves to restricted address: " + addr.getHostAddress());
                    }
                }
            } catch (java.net.UnknownHostException e) {
                throw new SecurityException("Cannot resolve host '" + host + "': " + e.getMessage(), e);
            }
        }
    }

    private boolean isRestrictedIp(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }

        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;

            // 0.0.0.0/8 (Current network)
            if (b0 == 0) return true;
            // 127.0.0.0/8 (Loopback)
            if (b0 == 127) return true;
            // 10.0.0.0/8 (Private RFC 1918)
            if (b0 == 10) return true;
            // 172.16.0.0/12 (Private RFC 1918)
            if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;
            // 192.168.0.0/16 (Private RFC 1918)
            if (b0 == 192 && b1 == 168) return true;
            // 169.254.0.0/16 (Link-local & cloud metadata)
            if (b0 == 169 && b1 == 254) return true;
            // 100.64.0.0/10 (Shared address space / Carrier-grade NAT)
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) return true;
            // Broadcast 255.255.255.255
            if (b0 == 255) return true;
        } else if (raw.length == 16) {
            // IPv6 ::1 loopback
            boolean allZeroExceptLast = true;
            for (int i = 0; i < 15; i++) {
                if (raw[i] != 0) { allZeroExceptLast = false; break; }
            }
            if (allZeroExceptLast && raw[15] == 1) return true;

            // Unique local fc00::/7 (0xfc or 0xfd)
            int b0 = raw[0] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) return true;

            // Link-local fe80::/10
            int b1 = raw[1] & 0xFF;
            if (b0 == 0xFE && (b1 & 0xC0) == 0x80) return true;

            // IPv4-mapped IPv6 (::ffff:x.x.x.x)
            if (raw[0] == 0 && raw[1] == 0 && raw[2] == 0 && raw[3] == 0
                    && raw[4] == 0 && raw[5] == 0 && raw[6] == 0 && raw[7] == 0
                    && raw[8] == 0 && raw[9] == 0 && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                byte[] v4 = new byte[]{raw[12], raw[13], raw[14], raw[15]};
                try {
                    return isRestrictedIp(InetAddress.getByAddress(v4));
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private byte[] loadFileAudio(String pathStr) throws FileNotFoundException, IOException {
        if (pathStr.contains("..")) {
            throw new SecurityException("Path traversal ('..') is not permitted: " + pathStr);
        }

        Path path = Path.of(pathStr).normalize();
        if (!Files.exists(path)) {
            throw new FileNotFoundException("Announcement file not found: " + pathStr);
        }

        // Jail check: if promptDirectory is configured and exists, restrict paths outside
        String jailDir = config.getPromptDirectory();
        if (jailDir != null && !jailDir.isBlank()) {
            Path jailPath = Path.of(jailDir).toAbsolutePath().normalize();
            if (Files.exists(jailPath)) {
                Path absPath = path.toAbsolutePath().normalize();
                if (!absPath.startsWith(jailPath)) {
                    throw new SecurityException("File path '" + pathStr + "' is outside allowed prompt directory '" + jailDir + "'");
                }
            }
        }

        long size = Files.size(path);
        if (size > config.getMaxAudioSizeBytes()) {
            throw new IOException("Audio file exceeds maximum allowed size (" + size + " > " + config.getMaxAudioSizeBytes() + " bytes)");
        }

        try (InputStream in = Files.newInputStream(path)) {
            return decodeWavOrPcm(readBoundedStream(in, config.getMaxAudioSizeBytes()));
        }
    }

    private byte[] readBoundedStream(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("Audio prompt size exceeds configured maximum limit of " + maxBytes + " bytes");
            }
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
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
