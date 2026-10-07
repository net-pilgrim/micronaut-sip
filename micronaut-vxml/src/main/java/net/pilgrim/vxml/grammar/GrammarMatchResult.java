package net.pilgrim.vxml.grammar;

import java.util.Objects;

/**
 * Result of matching DTMF input against VoiceXML grammars or choices.
 */
public class GrammarMatchResult {

    public enum Status {
        MATCH,
        NOMATCH,
        INCOMPLETE
    }

    private final Status status;
    private final String value;
    private final String nextTarget;

    private GrammarMatchResult(Status status, String value, String nextTarget) {
        this.status = status;
        this.value = value;
        this.nextTarget = nextTarget;
    }

    public static GrammarMatchResult match(String value) {
        return new GrammarMatchResult(Status.MATCH, value, null);
    }

    public static GrammarMatchResult matchChoice(String value, String nextTarget) {
        return new GrammarMatchResult(Status.MATCH, value, nextTarget);
    }

    public static GrammarMatchResult noMatch() {
        return new GrammarMatchResult(Status.NOMATCH, null, null);
    }

    public static GrammarMatchResult incomplete() {
        return new GrammarMatchResult(Status.INCOMPLETE, null, null);
    }

    public boolean isMatch() {
        return status == Status.MATCH;
    }

    public boolean isNoMatch() {
        return status == Status.NOMATCH;
    }

    public boolean isIncomplete() {
        return status == Status.INCOMPLETE;
    }

    public Status getStatus() {
        return status;
    }

    public String getValue() {
        return value;
    }

    public String getNextTarget() {
        return nextTarget;
    }

    @Override
    public String toString() {
        return "GrammarMatchResult{status=" + status + ", value='" + value + "', nextTarget='" + nextTarget + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GrammarMatchResult that)) return false;
        return status == that.status && Objects.equals(value, that.value) && Objects.equals(nextTarget, that.nextTarget);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, value, nextTarget);
    }
}
