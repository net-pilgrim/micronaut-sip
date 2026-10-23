package net.pilgrim.janus;

/**
 * Exception thrown when Janus Gateway returns an error.
 */
public class JanusException extends RuntimeException {

    public JanusException(String message) {
        super(message);
    }

    public JanusException(String message, Throwable cause) {
        super(message, cause);
    }
}
