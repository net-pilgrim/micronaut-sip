package net.pilgrim.sip.parser;

public class SipParseException extends RuntimeException {
    public SipParseException(String message) {
        super(message);
    }

    public SipParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
