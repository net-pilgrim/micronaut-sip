package net.pilgrim.ccxml.parser;

/**
 * Exception thrown when parsing a CCXML document fails.
 */
public class CcxmlParseException extends RuntimeException {

    public CcxmlParseException(String message) {
        super(message);
    }

    public CcxmlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
