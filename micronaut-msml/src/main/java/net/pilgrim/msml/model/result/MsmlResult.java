package net.pilgrim.msml.model.result;

import java.util.Collections;
import java.util.Map;

/**
 * Represents an MSML execution response &lt;result&gt; element (RFC 5707 Section 2.2).
 */
public record MsmlResult(String id, int responseCode, String message, Map<String, String> data) {

    public MsmlResult {
        if (message == null) message = (responseCode >= 200 && responseCode < 300) ? "OK" : "Error";
        data = data != null ? Map.copyOf(data) : Collections.emptyMap();
    }

    public static MsmlResult ok() {
        return new MsmlResult(null, 200, "OK", Collections.emptyMap());
    }

    public static MsmlResult ok(String id) {
        return new MsmlResult(id, 200, "OK", Collections.emptyMap());
    }

    public static MsmlResult ok(String id, Map<String, String> data) {
        return new MsmlResult(id, 200, "OK", data);
    }

    public static MsmlResult error(int code, String message) {
        return new MsmlResult(null, code, message, Collections.emptyMap());
    }

    public static MsmlResult error(String id, int code, String message) {
        return new MsmlResult(id, code, message, Collections.emptyMap());
    }

    public boolean isSuccess() {
        return responseCode >= 200 && responseCode < 300;
    }

    public boolean isError() {
        return !isSuccess();
    }
}
