package net.pilgrim.vxml.storage;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Objects;

/**
 * Protocol handler for classpath-bundled audio assets ({@code classpath:<path>}).
 */
@Singleton
public class ClasspathProtocolHandler implements MediaProtocolHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ClasspathProtocolHandler.class);

    @Override
    public String getScheme() {
        return "classpath";
    }

    @Override
    public byte[] read(URI uri) throws IOException {
        Objects.requireNonNull(uri, "uri must not be null");
        String uriStr = uri.toString();
        String path = uriStr.substring("classpath:".length());
        if (path.startsWith("/")) path = path.substring(1);

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = getClass().getClassLoader();

        try (InputStream is = cl.getResourceAsStream(path)) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            LOG.warn("Failed reading classpath audio: {}", path, e);
        }
        return new byte[0];
    }
}
