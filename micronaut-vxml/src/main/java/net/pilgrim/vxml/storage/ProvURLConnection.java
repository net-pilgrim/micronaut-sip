package net.pilgrim.vxml.storage;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;

/**
 * Custom {@link URLConnection} enabling transparent stream reading and writing
 * to {@code prov://} object storage and safe root assets.
 */
public class ProvURLConnection extends URLConnection {

    private byte[] readBytes;
    private ByteArrayOutputStream writeBuffer;

    public ProvURLConnection(URL url) {
        super(url);
    }

    @Override
    public void connect() throws IOException {
        if (!connected) {
            connected = true;
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        connect();
        if (readBytes == null) {
            try {
                readBytes = ProvStorageRegistry.get().loadAudio(url.toURI());
            } catch (Exception e) {
                throw new IOException("Failed reading prov:// stream: " + url, e);
            }
        }
        if (readBytes == null || readBytes.length == 0) {
            throw new FileNotFoundException("prov:// asset not found: " + url);
        }
        return new ByteArrayInputStream(readBytes);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        connect();
        if (writeBuffer == null) {
            writeBuffer = new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    super.close();
                    byte[] data = toByteArray();
                    try {
                        String contentType = getContentType();
                        ProvStorageRegistry.get().storeAudio(url.toURI().toString(), data, contentType);
                    } catch (Exception e) {
                        throw new IOException("Failed writing prov:// stream: " + url, e);
                    }
                }
            };
        }
        return writeBuffer;
    }

    @Override
    public String getContentType() {
        String path = url.getPath();
        if (path != null && path.endsWith(".wav")) {
            return "audio/wav";
        } else if (path != null && path.endsWith(".pcm")) {
            return "audio/x-raw-pcm";
        }
        return "audio/wav";
    }

    @Override
    public int getContentLength() {
        if (readBytes != null) {
            return readBytes.length;
        }
        return -1;
    }
}
