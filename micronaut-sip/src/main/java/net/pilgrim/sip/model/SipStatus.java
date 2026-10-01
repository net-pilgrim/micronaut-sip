package net.pilgrim.sip.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Standard SIP response status codes and default reason phrases (RFC 3261).
 */
public final class SipStatus {

    private SipStatus() {}

    // 1xx Provisional
    public static final int TRYING = 100;
    public static final int RINGING = 180;
    public static final int CALL_IS_BEING_FORWARDED = 181;
    public static final int QUEUED = 182;
    public static final int SESSION_PROGRESS = 183;

    // 2xx Success
    public static final int OK = 200;
    public static final int ACCEPTED = 202;

    // 3xx Redirection
    public static final int MULTIPLE_CHOICES = 300;
    public static final int MOVED_PERMANENTLY = 301;
    public static final int MOVED_TEMPORARILY = 302;
    public static final int USE_PROXY = 305;
    public static final int ALTERNATIVE_SERVICE = 380;

    // 4xx Client Error
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int PAYMENT_REQUIRED = 402;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int METHOD_NOT_ALLOWED = 405;
    public static final int NOT_ACCEPTABLE = 406;
    public static final int PROXY_AUTHENTICATION_REQUIRED = 407;
    public static final int REQUEST_TIMEOUT = 408;
    public static final int GONE = 410;
    public static final int REQUEST_ENTITY_TOO_LARGE = 413;
    public static final int REQUEST_URI_TOO_LONG = 414;
    public static final int UNSUPPORTED_MEDIA_TYPE = 415;
    public static final int UNSUPPORTED_URI_SCHEME = 416;
    public static final int BAD_EXTENSION = 420;
    public static final int INTERVAL_TOO_BRIEF = 423;
    public static final int TEMPORARILY_UNAVAILABLE = 480;
    public static final int CALL_TRANSACTION_DOES_NOT_EXIST = 481;
    public static final int LOOP_DETECTED = 482;
    public static final int TOO_MANY_HOPS = 483;
    public static final int ADDRESS_INCOMPLETE = 484;
    public static final int AMBIGUOUS = 485;
    public static final int BUSY_HERE = 486;
    public static final int REQUEST_TERMINATED = 487;
    public static final int NOT_ACCEPTABLE_HERE = 488;

    // 5xx Server Error
    public static final int SERVER_INTERNAL_ERROR = 500;
    public static final int NOT_IMPLEMENTED = 501;
    public static final int BAD_GATEWAY = 502;
    public static final int SERVICE_UNAVAILABLE = 503;
    public static final int SERVER_TIMEOUT = 504;
    public static final int VERSION_NOT_SUPPORTED = 505;
    public static final int MESSAGE_TOO_LARGE = 513;

    // 6xx Global Failure
    public static final int BUSY_EVERYWHERE = 600;
    public static final int DECLINE = 603;
    public static final int DOES_NOT_EXIST_ANYWHERE = 604;
    public static final int NOT_ACCEPTABLE_GLOBAL = 606;

    private static final Map<Integer, String> REASONS = new HashMap<>();

    static {
        REASONS.put(TRYING, "Trying");
        REASONS.put(RINGING, "Ringing");
        REASONS.put(CALL_IS_BEING_FORWARDED, "Call Is Being Forwarded");
        REASONS.put(QUEUED, "Queued");
        REASONS.put(SESSION_PROGRESS, "Session Progress");

        REASONS.put(OK, "OK");
        REASONS.put(ACCEPTED, "Accepted");

        REASONS.put(MULTIPLE_CHOICES, "Multiple Choices");
        REASONS.put(MOVED_PERMANENTLY, "Moved Permanently");
        REASONS.put(MOVED_TEMPORARILY, "Moved Temporarily");
        REASONS.put(USE_PROXY, "Use Proxy");
        REASONS.put(ALTERNATIVE_SERVICE, "Alternative Service");

        REASONS.put(BAD_REQUEST, "Bad Request");
        REASONS.put(UNAUTHORIZED, "Unauthorized");
        REASONS.put(PAYMENT_REQUIRED, "Payment Required");
        REASONS.put(FORBIDDEN, "Forbidden");
        REASONS.put(NOT_FOUND, "Not Found");
        REASONS.put(METHOD_NOT_ALLOWED, "Method Not Allowed");
        REASONS.put(NOT_ACCEPTABLE, "Not Acceptable");
        REASONS.put(PROXY_AUTHENTICATION_REQUIRED, "Proxy Authentication Required");
        REASONS.put(REQUEST_TIMEOUT, "Request Timeout");
        REASONS.put(GONE, "Gone");
        REASONS.put(REQUEST_ENTITY_TOO_LARGE, "Request Entity Too Large");
        REASONS.put(REQUEST_URI_TOO_LONG, "Request-URI Too Long");
        REASONS.put(UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type");
        REASONS.put(UNSUPPORTED_URI_SCHEME, "Unsupported URI Scheme");
        REASONS.put(BAD_EXTENSION, "Bad Extension");
        REASONS.put(INTERVAL_TOO_BRIEF, "Interval Too Brief");
        REASONS.put(TEMPORARILY_UNAVAILABLE, "Temporarily Unavailable");
        REASONS.put(CALL_TRANSACTION_DOES_NOT_EXIST, "Call/Transaction Does Not Exist");
        REASONS.put(LOOP_DETECTED, "Loop Detected");
        REASONS.put(TOO_MANY_HOPS, "Too Many Hops");
        REASONS.put(ADDRESS_INCOMPLETE, "Address Incomplete");
        REASONS.put(AMBIGUOUS, "Ambiguous");
        REASONS.put(BUSY_HERE, "Busy Here");
        REASONS.put(REQUEST_TERMINATED, "Request Terminated");
        REASONS.put(NOT_ACCEPTABLE_HERE, "Not Acceptable Here");

        REASONS.put(SERVER_INTERNAL_ERROR, "Server Internal Error");
        REASONS.put(NOT_IMPLEMENTED, "Not Implemented");
        REASONS.put(BAD_GATEWAY, "Bad Gateway");
        REASONS.put(SERVICE_UNAVAILABLE, "Service Unavailable");
        REASONS.put(SERVER_TIMEOUT, "Server Time-out");
        REASONS.put(VERSION_NOT_SUPPORTED, "Version Not Supported");
        REASONS.put(MESSAGE_TOO_LARGE, "Message Too Large");

        REASONS.put(BUSY_EVERYWHERE, "Busy Everywhere");
        REASONS.put(DECLINE, "Decline");
        REASONS.put(DOES_NOT_EXIST_ANYWHERE, "Does Not Exist Anywhere");
        REASONS.put(NOT_ACCEPTABLE_GLOBAL, "Not Acceptable");
    }

    public static String getReasonPhrase(int statusCode) {
        return REASONS.getOrDefault(statusCode, "Unknown Status");
    }
}
