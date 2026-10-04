package net.pilgrim.sip.model;

import java.util.*;

/**
 * Case-insensitive, multi-valued SIP headers container adhering to RFC 3261.
 * Supports compact header names (e.g. 'v' for 'Via', 'f' for 'From').
 */
public class SipHeaders implements Iterable<Map.Entry<String, List<String>>> {

    // Common standard header names
    public static final String VIA = "Via";
    public static final String FROM = "From";
    public static final String TO = "To";
    public static final String CALL_ID = "Call-ID";
    public static final String CSEQ = "CSeq";
    public static final String CONTACT = "Contact";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CONTENT_LENGTH = "Content-Length";
    public static final String MAX_FORWARDS = "Max-Forwards";
    public static final String ALLOW = "Allow";
    public static final String SUPPORTED = "Supported";
    public static final String USER_AGENT = "User-Agent";
    public static final String SERVER = "Server";
    public static final String EXPIRES = "Expires";
    public static final String DATE = "Date";
    public static final String ROUTE = "Route";
    public static final String RECORD_ROUTE = "Record-Route";
    public static final String AUTHORIZATION = "Authorization";
    public static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    public static final String REQUIRE = "Require";
    public static final String UNSUPPORTED = "Unsupported";
    public static final String RETRY_AFTER = "Retry-After";
    public static final String INFO_PACKAGE = "Info-Package"; // RFC 6086
    public static final String RECV_INFO = "Recv-Info";       // RFC 6086

    // Common standard MIME content types for DTMF
    public static final String APPLICATION_DTMF_RELAY = "application/dtmf-relay"; // RFC 2976 / RFC 6086
    public static final String APPLICATION_DTMF = "application/dtmf";

    private static final Map<String, String> COMPACT_TO_CANONICAL = new HashMap<>();
    private static final Map<String, String> CANONICAL_NAMES = new HashMap<>();

    static {
        registerCanonical(VIA);
        registerCanonical(FROM);
        registerCanonical(TO);
        registerCanonical(CALL_ID);
        registerCanonical(CSEQ);
        registerCanonical(CONTACT);
        registerCanonical(CONTENT_TYPE);
        registerCanonical(CONTENT_LENGTH);
        registerCanonical(MAX_FORWARDS);
        registerCanonical(ALLOW);
        registerCanonical(SUPPORTED);
        registerCanonical(USER_AGENT);
        registerCanonical(SERVER);
        registerCanonical(EXPIRES);
        registerCanonical(DATE);
        registerCanonical(ROUTE);
        registerCanonical(RECORD_ROUTE);
        registerCanonical(AUTHORIZATION);
        registerCanonical(WWW_AUTHENTICATE);
        registerCanonical(REQUIRE);
        registerCanonical(UNSUPPORTED);
        registerCanonical(RETRY_AFTER);
        registerCanonical(INFO_PACKAGE);
        registerCanonical(RECV_INFO);

        // Compact forms as defined in RFC 3261 Section 7.3.3
        COMPACT_TO_CANONICAL.put("v", VIA);
        COMPACT_TO_CANONICAL.put("f", FROM);
        COMPACT_TO_CANONICAL.put("t", TO);
        COMPACT_TO_CANONICAL.put("i", CALL_ID);
        COMPACT_TO_CANONICAL.put("m", CONTACT);
        COMPACT_TO_CANONICAL.put("c", CONTENT_TYPE);
        COMPACT_TO_CANONICAL.put("l", CONTENT_LENGTH);
        COMPACT_TO_CANONICAL.put("s", "Subject");
        COMPACT_TO_CANONICAL.put("k", SUPPORTED);
        COMPACT_TO_CANONICAL.put("e", "Content-Encoding");
        COMPACT_TO_CANONICAL.put("u", "Allow-Events");
        COMPACT_TO_CANONICAL.put("o", "Event");
        COMPACT_TO_CANONICAL.put("r", "Refer-To");
        COMPACT_TO_CANONICAL.put("b", "Referred-By");
    }

    private static void registerCanonical(String name) {
        CANONICAL_NAMES.put(name.toLowerCase(Locale.ROOT), name);
    }

    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private final Map<String, String> lowerToActualKey = new HashMap<>();

    public SipHeaders() {}

    public SipHeaders(SipHeaders other) {
        if (other != null) {
            for (Map.Entry<String, List<String>> entry : other.headers.entrySet()) {
                this.headers.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                this.lowerToActualKey.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getKey());
            }
        }
    }

    public static String normalizeHeaderName(String name) {
        if (name == null) return null;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (COMPACT_TO_CANONICAL.containsKey(lower)) {
            return COMPACT_TO_CANONICAL.get(lower);
        }
        return CANONICAL_NAMES.getOrDefault(lower, name.trim());
    }

    public SipHeaders add(String name, String value) {
        if (name == null || value == null) return this;
        String normalized = normalizeHeaderName(name);
        String lower = normalized.toLowerCase(Locale.ROOT);

        String actualKey = lowerToActualKey.computeIfAbsent(lower, k -> normalized);
        headers.computeIfAbsent(actualKey, k -> new ArrayList<>()).add(value.trim());
        return this;
    }

    public SipHeaders set(String name, String value) {
        if (name == null) return this;
        remove(name);
        if (value != null) {
            add(name, value);
        }
        return this;
    }

    public String get(String name) {
        if (name == null) return null;
        String lower = normalizeHeaderName(name).toLowerCase(Locale.ROOT);
        String actualKey = lowerToActualKey.get(lower);
        if (actualKey == null) return null;
        List<String> list = headers.get(actualKey);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    public List<String> getAll(String name) {
        if (name == null) return Collections.emptyList();
        String lower = normalizeHeaderName(name).toLowerCase(Locale.ROOT);
        String actualKey = lowerToActualKey.get(lower);
        if (actualKey == null) return Collections.emptyList();
        List<String> list = headers.get(actualKey);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public boolean contains(String name) {
        if (name == null) return false;
        String lower = normalizeHeaderName(name).toLowerCase(Locale.ROOT);
        return lowerToActualKey.containsKey(lower);
    }

    public SipHeaders remove(String name) {
        if (name == null) return this;
        String lower = normalizeHeaderName(name).toLowerCase(Locale.ROOT);
        String actualKey = lowerToActualKey.remove(lower);
        if (actualKey != null) {
            headers.remove(actualKey);
        }
        return this;
    }

    public Map<String, List<String>> asMap() {
        return Collections.unmodifiableMap(headers);
    }

    @Override
    public Iterator<Map.Entry<String, List<String>>> iterator() {
        return headers.entrySet().iterator();
    }

    // Convenience accessors
    public String getVia() { return get(VIA); }
    public List<String> getVias() { return getAll(VIA); }
    public SipHeaders addVia(String via) { return add(VIA, via); }
    public SipHeaders setTopVia(String via) {
        if (via == null) return this;
        String normalized = normalizeHeaderName(VIA);
        String lower = normalized.toLowerCase(Locale.ROOT);
        String actualKey = lowerToActualKey.computeIfAbsent(lower, k -> normalized);
        List<String> list = headers.computeIfAbsent(actualKey, k -> new ArrayList<>());
        if (list.isEmpty()) {
            list.add(via.trim());
        } else {
            list.set(0, via.trim());
        }
        return this;
    }

    public String getFrom() { return get(FROM); }
    public SipHeaders setFrom(String from) { return set(FROM, from); }

    public String getTo() { return get(TO); }
    public SipHeaders setTo(String to) { return set(TO, to); }

    public String getCallId() { return get(CALL_ID); }
    public SipHeaders setCallId(String callId) { return set(CALL_ID, callId); }

    public String getCSeq() { return get(CSEQ); }
    public SipHeaders setCSeq(String cseq) { return set(CSEQ, cseq); }

    public String getContact() { return get(CONTACT); }
    public SipHeaders setContact(String contact) { return set(CONTACT, contact); }

    public String getContentType() { return get(CONTENT_TYPE); }
    public SipHeaders setContentType(String contentType) { return set(CONTENT_TYPE, contentType); }

    public int getContentLength() {
        String val = get(CONTENT_LENGTH);
        if (val == null) return 0;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    public SipHeaders setContentLength(int length) {
        return set(CONTENT_LENGTH, String.valueOf(length));
    }

    public int getMaxForwards() {
        String val = get(MAX_FORWARDS);
        if (val == null) return 70;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return 70;
        }
    }
    public SipHeaders setMaxForwards(int maxForwards) {
        return set(MAX_FORWARDS, String.valueOf(maxForwards));
    }

    public String getUserAgent() { return get(USER_AGENT); }
    public SipHeaders setUserAgent(String userAgent) { return set(USER_AGENT, userAgent); }

    public String getServer() { return get(SERVER); }
    public SipHeaders setServer(String server) { return set(SERVER, server); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            for (String val : entry.getValue()) {
                sb.append(entry.getKey()).append(": ").append(val).append("\r\n");
            }
        }
        return sb.toString();
    }
}
