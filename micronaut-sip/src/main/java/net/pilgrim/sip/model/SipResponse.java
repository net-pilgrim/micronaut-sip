package net.pilgrim.sip.model;

/**
 * Models a SIP Response according to RFC 3261.
 */
public class SipResponse extends SipMessage {

    private int statusCode;
    private String reasonPhrase;

    public SipResponse() {}

    public SipResponse(int statusCode) {
        this(statusCode, SipStatus.getReasonPhrase(statusCode));
    }

    public SipResponse(int statusCode, String reasonPhrase) {
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase != null ? reasonPhrase : SipStatus.getReasonPhrase(statusCode);
    }

    @Override
    public boolean isRequest() {
        return false;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getReasonPhrase() {
        return reasonPhrase;
    }

    public void setReasonPhrase(String reasonPhrase) {
        this.reasonPhrase = reasonPhrase;
    }

    public boolean is1xx() {
        return statusCode >= 100 && statusCode < 200;
    }

    public boolean isProvisional() {
        return is1xx();
    }

    public boolean is2xx() {
        return statusCode >= 200 && statusCode < 300;
    }

    public boolean isSuccess() {
        return is2xx();
    }

    public boolean is3xx() {
        return statusCode >= 300 && statusCode < 400;
    }

    public boolean isRedirection() {
        return is3xx();
    }

    public boolean is4xx() {
        return statusCode >= 400 && statusCode < 500;
    }

    public boolean isClientError() {
        return is4xx();
    }

    public boolean is5xx() {
        return statusCode >= 500 && statusCode < 600;
    }

    public boolean isServerError() {
        return is5xx();
    }

    public boolean is6xx() {
        return statusCode >= 600 && statusCode < 700;
    }

    public boolean isGlobalFailure() {
        return is6xx();
    }

    public boolean isFinal() {
        return statusCode >= 200;
    }

    // Factory methods given a request
    public static SipResponse ok(SipRequest request) {
        return request.createResponse(SipStatus.OK);
    }

    public static SipResponse ok(SipRequest request, String body, String contentType) {
        SipResponse response = request.createResponse(SipStatus.OK);
        if (contentType != null) {
            response.getHeaders().setContentType(contentType);
        }
        if (body != null) {
            response.setBody(body);
        }
        return response;
    }

    public static SipResponse trying(SipRequest request) {
        return request.createResponse(SipStatus.TRYING);
    }

    public static SipResponse ringing(SipRequest request) {
        return request.createResponse(SipStatus.RINGING);
    }

    public static SipResponse sessionProgress(SipRequest request) {
        return request.createResponse(SipStatus.SESSION_PROGRESS);
    }

    public static SipResponse accepted(SipRequest request) {
        return request.createResponse(SipStatus.ACCEPTED);
    }

    public static SipResponse badRequest(SipRequest request) {
        return badRequest(request, "Bad Request");
    }

    public static SipResponse badRequest(SipRequest request, String reason) {
        return request.createResponse(SipStatus.BAD_REQUEST, reason != null ? reason : "Bad Request");
    }

    public static SipResponse badExtension(SipRequest request, String unsupported) {
        SipResponse res = request.createResponse(SipStatus.BAD_EXTENSION);
        if (unsupported != null && !unsupported.isEmpty()) {
            res.getHeaders().set(SipHeaders.UNSUPPORTED, unsupported);
        }
        return res;
    }

    public static SipResponse notFound(SipRequest request) {
        return request.createResponse(SipStatus.NOT_FOUND);
    }

    public static SipResponse methodNotAllowed(SipRequest request, String allowHeader) {
        SipResponse res = request.createResponse(SipStatus.METHOD_NOT_ALLOWED);
        if (allowHeader != null) {
            res.getHeaders().set(SipHeaders.ALLOW, allowHeader);
        }
        return res;
    }

    public static SipResponse busyHere(SipRequest request) {
        return request.createResponse(SipStatus.BUSY_HERE);
    }

    public static SipResponse serverError(SipRequest request, String reason) {
        return request.createResponse(SipStatus.SERVER_INTERNAL_ERROR, reason != null ? reason : "Server Internal Error");
    }

    public static SipResponse serviceUnavailable(SipRequest request) {
        return serviceUnavailable(request, "Service Unavailable");
    }

    public static SipResponse serviceUnavailable(SipRequest request, String reason) {
        return request.createResponse(SipStatus.SERVICE_UNAVAILABLE, reason != null ? reason : "Service Unavailable");
    }

    public static SipResponse messageTooLarge(SipRequest request) {
        return request.createResponse(SipStatus.MESSAGE_TOO_LARGE, SipStatus.getReasonPhrase(SipStatus.MESSAGE_TOO_LARGE));
    }

    public static SipResponse transactionDoesNotExist(SipRequest request) {
        return request.createResponse(SipStatus.CALL_TRANSACTION_DOES_NOT_EXIST);
    }

    public static SipResponse requestTerminated(SipRequest request) {
        return request.createResponse(SipStatus.REQUEST_TERMINATED);
    }

    @Override
    public String toString() {
        return sipVersion + " " + statusCode + " " + reasonPhrase;
    }
}
