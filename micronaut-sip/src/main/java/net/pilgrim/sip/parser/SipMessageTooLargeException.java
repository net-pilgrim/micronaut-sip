package net.pilgrim.sip.parser;

/**
 * Thrown when an incoming SIP message exceeds configured size limits (RFC 3261 Section 21.5.2).
 */
public class SipMessageTooLargeException extends SipParseException {

    public SipMessageTooLargeException(String message) {
        super(message);
    }

    public SipMessageTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }
}
