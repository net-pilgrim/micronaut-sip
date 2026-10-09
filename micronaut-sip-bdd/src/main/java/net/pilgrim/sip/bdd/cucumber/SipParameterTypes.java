package net.pilgrim.sip.bdd.cucumber;

import io.cucumber.java.ParameterType;
import net.pilgrim.sip.bdd.matcher.SipPredicates;
import net.pilgrim.sip.model.SipMethod;

import java.time.Duration;

/**
 * Custom Cucumber parameter types for SIP BDD testing.
 */
public class SipParameterTypes {

    @ParameterType("\\d+\\s*(?:ms|millis|milliseconds?|s|sec|seconds?)")
    public Duration duration(String value) {
        String trimmed = value.trim().toLowerCase();
        if (trimmed.endsWith("ms") || trimmed.contains("milli")) {
            long ms = Long.parseLong(trimmed.replaceAll("[^0-9]", ""));
            return Duration.ofMillis(ms);
        } else {
            long s = Long.parseLong(trimmed.replaceAll("[^0-9]", ""));
            return Duration.ofSeconds(s);
        }
    }

    @ParameterType("INVITE|ACK|BYE|CANCEL|REGISTER|OPTIONS|MESSAGE|INFO|PRACK|SUBSCRIBE|NOTIFY|REFER|UPDATE|PUBLISH")
    public SipMethod sipMethod(String value) {
        return SipMethod.valueOf(value.trim().toUpperCase());
    }

    @ParameterType("\\d{3}(?:\\s+[A-Za-z ]+)?")
    public int sipStatus(String value) {
        return SipPredicates.parseStatusCode(value);
    }
}
