package net.pilgrim.statemachine.parser;

public class StateMachineParseException extends RuntimeException {

    public StateMachineParseException(String message) {
        super(message);
    }

    public StateMachineParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
