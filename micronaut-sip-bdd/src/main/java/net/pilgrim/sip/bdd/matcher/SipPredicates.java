package net.pilgrim.sip.bdd.matcher;

import net.pilgrim.sip.model.SipMessage;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;

import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Predicates for matching SIP requests and responses in SipMailbox awaiters.
 */
public final class SipPredicates {

    private SipPredicates() {}

    public static Predicate<SipMessage> isRequest() {
        return msg -> msg instanceof SipRequest;
    }

    public static Predicate<SipMessage> isRequest(SipMethod method) {
        return msg -> msg instanceof SipRequest req && req.getMethod() == method;
    }

    public static Predicate<SipMessage> isRequest(String methodName) {
        return msg -> msg instanceof SipRequest req && req.getMethod().name().equalsIgnoreCase(methodName);
    }

    public static Predicate<SipMessage> isResponse() {
        return msg -> msg instanceof SipResponse;
    }

    public static Predicate<SipMessage> isResponse(int statusCode) {
        return msg -> msg instanceof SipResponse resp && resp.getStatusCode() == statusCode;
    }

    public static Predicate<SipMessage> isStatus(String statusStr) {
        int code = parseStatusCode(statusStr);
        return isResponse(code);
    }

    public static Predicate<SipMessage> hasHeader(String headerName) {
        return msg -> msg.getHeaders().get(headerName) != null;
    }

    public static Predicate<SipMessage> hasHeaderValue(String headerName, String expectedValue) {
        return msg -> {
            String val = msg.getHeaders().get(headerName);
            return val != null && val.trim().equalsIgnoreCase(expectedValue.trim());
        };
    }

    public static Predicate<SipMessage> hasHeaderMatching(String headerName, String regex) {
        Pattern pattern = Pattern.compile(regex);
        return msg -> {
            String val = msg.getHeaders().get(headerName);
            return val != null && pattern.matcher(val).find();
        };
    }

    public static Predicate<SipMessage> hasHeaderContaining(String headerName, String paramName, String paramValue) {
        return msg -> {
            String val = msg.getHeaders().get(headerName);
            if (val == null) {
                return false;
            }
            if (paramValue != null) {
                String search = paramName + "=" + paramValue;
                return val.contains(search);
            } else {
                return val.contains(paramName);
            }
        };
    }

    public static Predicate<SipMessage> bodyContains(String text) {
        return msg -> msg.getBodyAsString().contains(text);
    }

    public static int parseStatusCode(String statusStr) {
        if (statusStr == null || statusStr.isBlank()) {
            throw new IllegalArgumentException("Status string cannot be blank");
        }
        String trimmed = statusStr.trim();
        String firstToken = trimmed.split("\\s+")[0];
        try {
            return Integer.parseInt(firstToken);
        } catch (NumberFormatException e) {
            // E.g. "Ringing" -> 180, "OK" -> 200, "Trying" -> 100, etc.
            return switch (trimmed.toUpperCase()) {
                case "TRYING", "100 TRYING" -> 100;
                case "RINGING", "180 RINGING" -> 180;
                case "SESSION PROGRESS", "183 SESSION PROGRESS" -> 183;
                case "OK", "200 OK" -> 200;
                case "ACCEPTED", "202 ACCEPTED" -> 202;
                case "UNAUTHORIZED", "401 UNAUTHORIZED" -> 401;
                case "FORBIDDEN", "403 FORBIDDEN" -> 403;
                case "NOT FOUND", "404 NOT FOUND" -> 404;
                case "METHOD NOT ALLOWED", "405 METHOD NOT ALLOWED" -> 405;
                case "PROXY AUTHENTICATION REQUIRED", "407 PROXY AUTHENTICATION REQUIRED" -> 407;
                case "REQUEST TIMEOUT", "408 REQUEST TIMEOUT" -> 408;
                case "BUSY HERE", "486 BUSY HERE" -> 486;
                case "REQUEST TERMINATED", "487 REQUEST TERMINATED" -> 487;
                case "SERVER INTERNAL ERROR", "500 SERVER INTERNAL ERROR" -> 500;
                case "DECLINE", "603 DECLINE" -> 603;
                default -> throw new IllegalArgumentException("Cannot parse SIP status code from: " + statusStr);
            };
        }
    }
}
