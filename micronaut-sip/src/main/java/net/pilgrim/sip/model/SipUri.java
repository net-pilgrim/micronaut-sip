package net.pilgrim.sip.model;

import java.util.*;

/**
 * Models a SIP URI according to RFC 3261 Section 19.1.
 * Example: sip:alice@example.com:5060;transport=udp
 */
public class SipUri {

    private String scheme = "sip";
    private String user;
    private String password;
    private String host;
    private int port = -1;
    private final Map<String, String> parameters = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();

    public SipUri() {}

    public SipUri(String user, String host) {
        this.user = user;
        this.host = host;
    }

    public SipUri(String user, String host, int port) {
        this.user = user;
        this.host = host;
        this.port = port;
    }

    /**
     * Extracts and parses the SIP URI from an address header like From, To, or Contact.
     * Handles display names and header parameters:
     * e.g. "Alice" &lt;sip:alice@example.com&gt;;tag=123 -&gt; sip:alice@example.com
     */
    public static SipUri extractUri(String addressHeader) {
        if (addressHeader == null || addressHeader.trim().isEmpty()) {
            return null;
        }
        String s = addressHeader.trim();
        int start = s.indexOf('<');
        int end = s.indexOf('>', start);
        if (start != -1 && end != -1) {
            return parse(s.substring(start + 1, end).trim());
        }
        int semi = s.indexOf(';');
        if (semi != -1) {
            return parse(s.substring(0, semi).trim());
        }
        return parse(s);
    }

    public static SipUri parse(String rawUri) {
        if (rawUri == null) {
            throw new IllegalArgumentException("SIP URI cannot be null");
        }
        String s = rawUri.trim();
        // Remove surrounding angle brackets if present: <sip:...>
        if (s.startsWith("<") && s.endsWith(">")) {
            s = s.substring(1, s.length() - 1).trim();
        }

        SipUri uri = new SipUri();
        int schemeColon = s.indexOf(':');
        if (schemeColon == -1) {
            throw new IllegalArgumentException("Invalid SIP URI (missing scheme): " + rawUri);
        }
        uri.scheme = s.substring(0, schemeColon).toLowerCase(Locale.ROOT);
        if (!"sip".equals(uri.scheme) && !"sips".equals(uri.scheme)) {
            throw new IllegalArgumentException("Unsupported scheme in SIP URI: " + uri.scheme);
        }

        String remainder = s.substring(schemeColon + 1);

        // Check for headers (?)
        int qIdx = remainder.indexOf('?');
        if (qIdx != -1) {
            String headerPart = remainder.substring(qIdx + 1);
            remainder = remainder.substring(0, qIdx);
            for (String h : headerPart.split("&")) {
                int eq = h.indexOf('=');
                if (eq != -1) {
                    uri.headers.put(h.substring(0, eq), h.substring(eq + 1));
                } else {
                    uri.headers.put(h, "");
                }
            }
        }

        // Check for parameters (;)
        int semiIdx = remainder.indexOf(';');
        if (semiIdx != -1) {
            String paramPart = remainder.substring(semiIdx + 1);
            remainder = remainder.substring(0, semiIdx);
            for (String p : paramPart.split(";")) {
                int eq = p.indexOf('=');
                if (eq != -1) {
                    uri.parameters.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
                } else {
                    uri.parameters.put(p.trim(), "");
                }
            }
        }

        // Now remainder is [userinfo@]hostport
        int atIdx = remainder.indexOf('@');
        String hostPort;
        if (atIdx != -1) {
            String userInfo = remainder.substring(0, atIdx);
            hostPort = remainder.substring(atIdx + 1);
            int colonUser = userInfo.indexOf(':');
            if (colonUser != -1) {
                uri.user = userInfo.substring(0, colonUser);
                uri.password = userInfo.substring(colonUser + 1);
            } else {
                uri.user = userInfo;
            }
        } else {
            hostPort = remainder;
        }

        // hostport can be host or host:port or [ipv6]:port
        if (hostPort.startsWith("[")) {
            int closeBracket = hostPort.indexOf(']');
            if (closeBracket != -1) {
                uri.host = hostPort.substring(1, closeBracket);
                if (hostPort.length() > closeBracket + 1 && hostPort.charAt(closeBracket + 1) == ':') {
                    uri.port = Integer.parseInt(hostPort.substring(closeBracket + 2));
                }
            } else {
                uri.host = hostPort;
            }
        } else {
            int colonHost = hostPort.indexOf(':');
            if (colonHost != -1) {
                uri.host = hostPort.substring(0, colonHost);
                uri.port = Integer.parseInt(hostPort.substring(colonHost + 1));
            } else {
                uri.host = hostPort;
            }
        }

        return uri;
    }

    public String getScheme() { return scheme; }
    public void setScheme(String scheme) { this.scheme = scheme; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public Map<String, String> getParameters() { return parameters; }
    public String getParameter(String name) { return parameters.get(name); }
    public void setParameter(String name, String value) { parameters.put(name, value); }

    public Map<String, String> getHeaders() { return headers; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append(":");
        if (user != null && !user.isEmpty()) {
            sb.append(user);
            if (password != null) {
                sb.append(":").append(password);
            }
            sb.append("@");
        }
        if (host != null && host.contains(":") && !host.startsWith("[")) {
            sb.append("[").append(host).append("]");
        } else {
            sb.append(host != null ? host : "localhost");
        }
        if (port > 0) {
            sb.append(":").append(port);
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            sb.append(";").append(entry.getKey());
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                sb.append("=").append(entry.getValue());
            }
        }
        if (!headers.isEmpty()) {
            sb.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (!first) sb.append("&");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SipUri sipUri = (SipUri) o;
        return port == sipUri.port &&
                Objects.equals(scheme, sipUri.scheme) &&
                Objects.equals(user, sipUri.user) &&
                Objects.equals(host, sipUri.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, user, host, port);
    }
}
