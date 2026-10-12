package net.pilgrim.vxml.storage;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * {@link URLStreamHandler} for the {@code prov://} protocol.
 */
public class ProvURLStreamHandler extends URLStreamHandler {

    @Override
    protected URLConnection openConnection(URL u) throws IOException {
        return new ProvURLConnection(u);
    }
}
