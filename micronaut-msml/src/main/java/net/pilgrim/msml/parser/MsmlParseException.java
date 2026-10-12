package net.pilgrim.msml.parser;

/**
 * Exception thrown when MSML document parsing fails.
 */
public class MsmlParseException extends RuntimeException {

    public MsmlParseException(String message) {
        super(message);
    }

    public MsmlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
