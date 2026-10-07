package net.pilgrim.sip.session.state;

import io.micronaut.core.annotation.Nullable;
import net.pilgrim.sip.session.SipSession;

/**
 * Result of a state transition executed on a SIP dialog session statechart.
 */
public record DialogTransitionResult(
        boolean successful,
        SipSession.State previousState,
        SipSession.State newState,
        String message,
        @Nullable Object payload
) {

    public static DialogTransitionResult success(SipSession.State from, SipSession.State to, String message) {
        return new DialogTransitionResult(true, from, to, message, null);
    }

    public static DialogTransitionResult success(SipSession.State from, SipSession.State to, String message, Object payload) {
        return new DialogTransitionResult(true, from, to, message, payload);
    }

    public static DialogTransitionResult noop(SipSession.State current, String message) {
        return new DialogTransitionResult(true, current, current, message, null);
    }

    public static DialogTransitionResult rejected(SipSession.State current, String message) {
        return new DialogTransitionResult(false, current, current, message, null);
    }

    public boolean isConfirmed() {
        return newState == SipSession.State.CONFIRMED;
    }

    public boolean isTerminated() {
        return newState == SipSession.State.TERMINATED;
    }

    public boolean isEarly() {
        return newState == SipSession.State.EARLY;
    }

    public boolean isRejected() {
        return !successful;
    }
}
