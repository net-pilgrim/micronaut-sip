package net.pilgrim.vxml.storage;

import java.net.URLStreamHandler;
import java.net.spi.URLStreamHandlerProvider;

/**
 * Service provider making {@code prov://} natively pluggable in Java's {@link java.net.URL}
 * and {@link java.net.URI#toURL()}.
 */
public class ProvURLStreamHandlerProvider extends URLStreamHandlerProvider {

    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        if ("prov".equalsIgnoreCase(protocol)) {
            return new ProvURLStreamHandler();
        }
        return null;
    }
}
