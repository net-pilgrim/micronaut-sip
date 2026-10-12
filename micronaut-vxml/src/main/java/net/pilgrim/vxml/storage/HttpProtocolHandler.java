package net.pilgrim.vxml.storage;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Protocol handler for remote HTTP and HTTPS audio resources.
 */
@Singleton
public class HttpProtocolHandler implements MediaProtocolHandler {

    private static final Logger LOG = LoggerFactory.getLogger(HttpProtocolHandler.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String getScheme() {
        return "http";
    }

    @Override
    public boolean supportsRead(URI uri) {
        if (uri == null) return false;
        String s = uri.getScheme();
        return "http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s);
    }

    @Override
    public byte[] read(URI uri) throws IOException {
        Objects.requireNonNull(uri, "uri must not be null");
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                return resp.body();
            } else {
                LOG.warn("HTTP audio fetch for {} returned status {}", uri, resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP audio fetch interrupted for " + uri, e);
        } catch (Exception e) {
            LOG.warn("Failed fetching HTTP audio from {}: {}", uri, e.getMessage());
        }
        return new byte[0];
    }
}
