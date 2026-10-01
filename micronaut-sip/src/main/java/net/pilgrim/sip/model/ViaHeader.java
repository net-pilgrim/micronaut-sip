package net.pilgrim.sip.model;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Models a SIP Via header according to RFC 3261 and RFC 3581.
 * Example: SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-123;rport=45678;received=203.0.113.195
 */
public class ViaHeader {

    private String protocol = "SIP/2.0/UDP";
    private String host;
    private int port = -1;
    private final Map<String, String> parameters = new LinkedHashMap<>();

    public ViaHeader() {}

    public ViaHeader(String protocol, String host, int port) {
        this.protocol = protocol;
        this.host = host;
        this.port = port;
    }

    public static ViaHeader parse(String rawVia) {
        if (rawVia == null || rawVia.trim().isEmpty()) {
            throw new IllegalArgumentException("Via header cannot be null or empty");
        }
        String s = rawVia.trim();
        // If comma-separated multiple Vias are present on a single header line, extract the topmost
        int commaIdx = s.indexOf(',');
        if (commaIdx != -1) {
            s = s.substring(0, commaIdx).trim();
        }

        ViaHeader via = new ViaHeader();
        int spaceIdx = s.indexOf(' ');
        if (spaceIdx == -1) {
            spaceIdx = s.indexOf('\t');
        }
        if (spaceIdx == -1) {
            throw new IllegalArgumentException("Invalid Via header (missing sent-by): " + rawVia);
        }

        via.protocol = s.substring(0, spaceIdx).trim();
        String remainder = s.substring(spaceIdx + 1).trim();

        int semiIdx = remainder.indexOf(';');
        String sentBy;
        if (semiIdx != -1) {
            sentBy = remainder.substring(0, semiIdx).trim();
            String paramPart = remainder.substring(semiIdx + 1).trim();
            for (String param : paramPart.split(";")) {
                param = param.trim();
                if (param.isEmpty()) continue;
                int eqIdx = param.indexOf('=');
                if (eqIdx != -1) {
                    via.parameters.put(param.substring(0, eqIdx).trim().toLowerCase(Locale.ROOT),
                            param.substring(eqIdx + 1).trim());
                } else {
                    via.parameters.put(param.toLowerCase(Locale.ROOT), "");
                }
            }
        } else {
            sentBy = remainder;
        }

        // Parse sentBy host and optional port
        if (sentBy.startsWith("[")) {
            int closeBracket = sentBy.indexOf(']');
            if (closeBracket != -1) {
                via.host = sentBy.substring(1, closeBracket);
                if (sentBy.length() > closeBracket + 1 && sentBy.charAt(closeBracket + 1) == ':') {
                    try {
                        via.port = Integer.parseInt(sentBy.substring(closeBracket + 2));
                    } catch (NumberFormatException ignored) {
                        via.port = -1;
                    }
                }
            } else {
                via.host = sentBy;
            }
        } else {
            int colonIdx = sentBy.indexOf(':');
            if (colonIdx != -1) {
                via.host = sentBy.substring(0, colonIdx);
                try {
                    via.port = Integer.parseInt(sentBy.substring(colonIdx + 1));
                } catch (NumberFormatException ignored) {
                    via.port = -1;
                }
            } else {
                via.host = sentBy;
            }
        }

        return via;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getBranch() {
        return parameters.get("branch");
    }

    public void setBranch(String branch) {
        if (branch != null) {
            parameters.put("branch", branch);
        } else {
            parameters.remove("branch");
        }
    }

    public String getReceived() {
        return parameters.get("received");
    }

    public void setReceived(String received) {
        if (received != null) {
            parameters.put("received", received);
        } else {
            parameters.remove("received");
        }
    }

    public boolean hasRport() {
        return parameters.containsKey("rport");
    }

    public int getRport() {
        String val = parameters.get("rport");
        if (val == null || val.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public void setRport(int rport) {
        if (rport > 0) {
            parameters.put("rport", String.valueOf(rport));
        } else {
            parameters.put("rport", "");
        }
    }

    public void enableRport() {
        if (!parameters.containsKey("rport")) {
            parameters.put("rport", "");
        }
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public String getParameter(String name) {
        if (name == null) return null;
        return parameters.get(name.toLowerCase(Locale.ROOT));
    }

    public void setParameter(String name, String value) {
        if (name != null) {
            parameters.put(name.toLowerCase(Locale.ROOT), value != null ? value : "");
        }
    }

    /**
     * Resolves the response destination address according to RFC 3261 Section 18.2.2 and RFC 3581 Section 4.
     */
    public InetSocketAddress resolveResponseAddress(InetSocketAddress fallback) {
        String targetHost = (getReceived() != null && !getReceived().isEmpty()) ? getReceived() : host;
        int targetPort;
        if (hasRport() && getRport() > 0) {
            targetPort = getRport();
        } else if (port > 0) {
            targetPort = port;
        } else if (fallback != null && fallback.getPort() > 0) {
            targetPort = fallback.getPort();
        } else {
            targetPort = 5060;
        }

        if (targetHost == null || targetHost.isEmpty()) {
            if (fallback != null) {
                return fallback;
            }
            targetHost = "127.0.0.1";
        }

        return new InetSocketAddress(targetHost, targetPort);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(protocol != null ? protocol : "SIP/2.0/UDP").append(" ");
        if (host != null && host.contains(":") && !host.startsWith("[")) {
            sb.append("[").append(host).append("]");
        } else {
            sb.append(host != null ? host : "127.0.0.1");
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
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ViaHeader viaHeader = (ViaHeader) o;
        return port == viaHeader.port &&
                Objects.equals(protocol, viaHeader.protocol) &&
                Objects.equals(host, viaHeader.host) &&
                Objects.equals(parameters, viaHeader.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocol, host, port, parameters);
    }
}
