package net.pilgrim.vxml.loader;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.vxml.ast.VxmlDocument;
import net.pilgrim.vxml.config.VxmlConfiguration;
import net.pilgrim.vxml.parser.VxmlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads and parses VoiceXML documents from inline XML, classpath resources,
 * local filesystem files, or remote HTTP/HTTPS endpoints with SSRF guardrails.
 */
@Singleton
public class VxmlDocumentLoader {

    private static final Logger LOG = LoggerFactory.getLogger(VxmlDocumentLoader.class);

    private final VxmlConfiguration config;
    private final VxmlParser parser;

    @Inject
    public VxmlDocumentLoader(VxmlConfiguration config) {
        this.config = config != null ? config : new VxmlConfiguration();
        this.parser = new VxmlParser();
    }

    public VxmlDocumentLoader() {
        this(new VxmlConfiguration());
    }

    public VxmlDocument loadDocument(String uri) throws Exception {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("VoiceXML URI cannot be blank");
        }

        String rawUri = uri.trim();

        // 1. Inline XML
        if (rawUri.startsWith("<") || rawUri.startsWith("inline:")) {
            String xml = rawUri.startsWith("inline:") ? rawUri.substring("inline:".length()).trim() : rawUri;
            return parser.parse(xml);
        }

        // 2. Classpath resource: classpath:vxml/menu.vxml or /vxml/menu.vxml
        if (rawUri.startsWith("classpath:") || rawUri.startsWith("/vxml/")) {
            String path = rawUri.startsWith("classpath:") ? rawUri.substring("classpath:".length()) : rawUri;
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            return loadFromClasspath(path);
        }

        // 3. HTTP / HTTPS endpoint
        if (rawUri.startsWith("http://") || rawUri.startsWith("https://")) {
            return loadFromHttp(rawUri);
        }

        // 4. Local file: file:///... or filesystem path
        String filePath = rawUri;
        if (filePath.startsWith("file://")) {
            filePath = filePath.substring("file://".length());
        }
        return loadFromFile(filePath);
    }

    private VxmlDocument loadFromClasspath(String resourcePath) throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = getClass().getClassLoader();
        }

        InputStream is = cl.getResourceAsStream(resourcePath);
        if (is == null && !resourcePath.startsWith("/")) {
            is = cl.getResourceAsStream("/" + resourcePath);
        }
        if (is == null) {
            throw new FileNotFoundException("VoiceXML classpath resource not found: " + resourcePath);
        }

        try (InputStream in = is) {
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parser.parse(xml);
        }
    }

    private VxmlDocument loadFromFile(String filePath) throws Exception {
        Path path = Paths.get(filePath).normalize();

        // Directory traversal protection
        if (filePath.contains("..")) {
            throw new SecurityException("Path traversal forbidden: " + filePath);
        }

        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new FileNotFoundException("VoiceXML file not found: " + filePath);
        }

        int maxDocSize = config.getMaxDocumentSizeBytes();
        long size = Files.size(path);
        if (size > maxDocSize) {
            throw new IllegalArgumentException("VoiceXML file exceeds maximum allowed size (" + maxDocSize + " bytes)");
        }

        String xml = Files.readString(path, StandardCharsets.UTF_8);
        return parser.parse(xml);
    }

    private VxmlDocument loadFromHttp(String urlString) throws Exception {
        if (!config.isHttpEnabled()) {
            throw new SecurityException("HTTP loading disabled by vxml.http-enabled=false");
        }

        URI uri = URI.create(urlString);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Invalid HTTP URL host: " + urlString);
        }

        if (!config.getAllowedHosts().isEmpty() && !config.getAllowedHosts().contains(host.toLowerCase())) {
            throw new SecurityException("Host not in allowed hosts list: " + host);
        }

        if (config.isSsrfProtectionEnabled()) {
            validateSsrf(host);
        }

        URL url = uri.toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(config.getHttpConnectTimeoutMs());
        conn.setReadTimeout(config.getHttpReadTimeoutMs());
        conn.setRequestProperty("Accept", "application/voicexml+xml, text/xml, application/xml");

        int responseCode = conn.getResponseCode();
        if (responseCode == 404) {
            throw new FileNotFoundException("Remote VoiceXML document not found: " + urlString);
        }
        if (responseCode >= 300 && responseCode < 400) {
            throw new SecurityException("HTTP redirects are not followed for security: " + responseCode);
        }
        if (responseCode != 200) {
            throw new IllegalArgumentException("HTTP error " + responseCode + " loading VoiceXML: " + urlString);
        }

        int maxDocSize = config.getMaxDocumentSizeBytes();
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buf)) != -1) {
                total += read;
                if (total > maxDocSize) {
                    throw new IllegalArgumentException("Remote VoiceXML document exceeds maximum size: " + maxDocSize);
                }
                out.write(buf, 0, read);
            }
            String xml = out.toString(StandardCharsets.UTF_8);
            return parser.parse(xml);
        } finally {
            conn.disconnect();
        }
    }

    private void validateSsrf(String host) throws Exception {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress addr : addresses) {
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
                throw new SecurityException("SSRF blocked: host '" + host + "' resolves to restricted address: " + addr.getHostAddress());
            }
            byte[] raw = addr.getAddress();
            if (raw.length == 4) {
                int b0 = raw[0] & 0xFF;
                int b1 = raw[1] & 0xFF;
                // Cloud metadata IP 169.254.169.254 or link-local 169.254.x.x
                if (b0 == 169 && b1 == 254) {
                    throw new SecurityException("SSRF blocked: host '" + host + "' resolves to link-local/cloud metadata: " + addr.getHostAddress());
                }
                // RFC 1918
                if (b0 == 10 || (b0 == 172 && (b1 >= 16 && b1 <= 31)) || (b0 == 192 && b1 == 168)) {
                    throw new SecurityException("SSRF blocked: host '" + host + "' resolves to private network address: " + addr.getHostAddress());
                }
            }
        }
    }

    /**
     * Resolves a target URI relative to a base VoiceXML URI.
     */
    public String resolveUri(String baseUri, String targetUri) {
        if (targetUri == null || targetUri.isBlank()) {
            return baseUri;
        }
        String cleanTarget = targetUri.trim();
        if (cleanTarget.startsWith("#")) {
            String base = (baseUri != null) ? baseUri.split("#")[0] : "";
            return base + cleanTarget;
        }
        if (cleanTarget.startsWith("http://") || cleanTarget.startsWith("https://")
                || cleanTarget.startsWith("file://") || cleanTarget.startsWith("classpath:")
                || cleanTarget.startsWith("inline:")) {
            return cleanTarget;
        }
        if (baseUri != null && !baseUri.isBlank()) {
            int lastSlash = baseUri.lastIndexOf('/');
            if (lastSlash != -1) {
                return baseUri.substring(0, lastSlash + 1) + cleanTarget;
            }
        }
        return cleanTarget;
    }
}
