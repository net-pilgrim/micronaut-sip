package net.pilgrim.vxml.loader;

/**
 * Functional interface for loading audio prompt bytes given a URI.
 */
@FunctionalInterface
public interface VxmlAudioLoader {

    /**
     * Loads audio data from the given URI.
     *
     * @param uri URI string (e.g. tone:..., builtin:..., classpath:..., file:..., http:...)
     * @return 8000 Hz, 16-bit mono signed PCM-16LE audio bytes
     * @throws Exception if loading fails
     */
    byte[] loadAudio(String uri) throws Exception;
}
