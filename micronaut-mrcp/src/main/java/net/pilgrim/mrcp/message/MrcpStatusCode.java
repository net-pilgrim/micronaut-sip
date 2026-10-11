package net.pilgrim.mrcp.message;

/**
 * Standard MRCPv2 response status codes per RFC 6787 §5.2.
 */
public final class MrcpStatusCode {

    private MrcpStatusCode() {}

    // 2xx Success
    public static final int SUCCESS = 200;
    public static final int SUCCESS_ACTIVE_OPERATION = 201;
    public static final int IN_PROGRESS = 202;

    // 4xx Client Error
    public static final int METHOD_NOT_ALLOWED = 401;
    public static final int METHOD_NOT_VALID_IN_STATE = 402;
    public static final int UNSUPPORTED_HEADER_FIELD = 403;
    public static final int ILLEGAL_VALUE_FOR_HEADER = 404;
    public static final int RESOURCE_NOT_FOUND = 405;
    public static final int MANDATORY_HEADER_MISSING = 406;
    public static final int METHOD_OR_OPERATION_FAILED = 407;
    public static final int UNRECOGNIZED_MESSAGE_ENTITY = 408;

    // 5xx Server Error
    public static final int SERVER_INTERNAL_ERROR = 500;
    public static final int PROTOCOL_VERSION_NOT_SUPPORTED = 501;
    public static final int PROXY_TIMEOUT = 502;
    public static final int MESSAGE_TOO_LARGE = 503;

    public static String getReasonPhrase(int code) {
        return switch (code) {
            case SUCCESS -> "Success";
            case SUCCESS_ACTIVE_OPERATION -> "Success with active operation";
            case IN_PROGRESS -> "In Progress";
            case METHOD_NOT_ALLOWED -> "Method Not Allowed";
            case METHOD_NOT_VALID_IN_STATE -> "Method Not Valid with this Resource in this State";
            case UNSUPPORTED_HEADER_FIELD -> "Unsupported Header Field";
            case ILLEGAL_VALUE_FOR_HEADER -> "Illegal Value for Header Field";
            case RESOURCE_NOT_FOUND -> "Resource Not Found";
            case MANDATORY_HEADER_MISSING -> "Mandatory Header Field Missing";
            case METHOD_OR_OPERATION_FAILED -> "Method or Operation Failed";
            case UNRECOGNIZED_MESSAGE_ENTITY -> "Unrecognized Message Entity";
            case SERVER_INTERNAL_ERROR -> "Server Internal Error";
            case PROTOCOL_VERSION_NOT_SUPPORTED -> "Protocol Version Not Supported";
            case PROXY_TIMEOUT -> "Proxy Timeout";
            case MESSAGE_TOO_LARGE -> "Message Too Large";
            default -> "Unknown Status (" + code + ")";
        };
    }
}
