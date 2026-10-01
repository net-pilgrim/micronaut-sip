package net.pilgrim.sip.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Utility for matching IP addresses against exact IP addresses and CIDR subnets (IPv4 and IPv6).
 */
public class IpMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(IpMatcher.class);

    private final List<Rule> rules = new ArrayList<>();

    public IpMatcher(Collection<String> patterns) {
        if (patterns != null) {
            for (String pattern : patterns) {
                if (pattern != null && !pattern.trim().isEmpty()) {
                    addRule(pattern.trim());
                }
            }
        }
    }

    private void addRule(String pattern) {
        try {
            if (pattern.contains("/")) {
                String[] parts = pattern.split("/");
                InetAddress base = InetAddress.getByName(parts[0].trim());
                int prefixLength = Integer.parseInt(parts[1].trim());
                rules.add(new CidrRule(base, prefixLength, pattern));
            } else {
                InetAddress exact = InetAddress.getByName(pattern);
                rules.add(new ExactRule(exact, pattern));
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse IP matcher rule '{}': {}", pattern, e.getMessage());
            rules.add(new StringRule(pattern));
        }
    }

    /**
     * Checks if the given address matches any configured rule.
     *
     * @param address the target InetAddress
     * @param fallbackString fallback host or IP string if InetAddress is null
     * @return true if whitelisted, false otherwise
     */
    public boolean matches(InetAddress address, String fallbackString) {
        if (rules.isEmpty()) {
            return false;
        }
        for (Rule rule : rules) {
            if (rule.matches(address, fallbackString)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    private interface Rule {
        boolean matches(InetAddress address, String fallback);
    }

    private static class ExactRule implements Rule {
        private final InetAddress exact;
        private final String raw;

        ExactRule(InetAddress exact, String raw) {
            this.exact = exact;
            this.raw = raw;
        }

        @Override
        public boolean matches(InetAddress address, String fallback) {
            if (address != null && exact.equals(address)) {
                return true;
            }
            return fallback != null && raw.equalsIgnoreCase(fallback);
        }
    }

    private static class CidrRule implements Rule {
        private final byte[] baseBytes;
        private final int prefixLength;
        private final String raw;

        CidrRule(InetAddress base, int prefixLength, String raw) {
            this.baseBytes = base.getAddress();
            this.prefixLength = prefixLength;
            this.raw = raw;
        }

        @Override
        public boolean matches(InetAddress address, String fallback) {
            if (address == null) {
                return false;
            }
            byte[] targetBytes = address.getAddress();
            if (targetBytes.length != baseBytes.length) {
                return false;
            }
            if (prefixLength < 0 || prefixLength > targetBytes.length * 8) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (targetBytes[i] != baseBytes[i]) {
                    return false;
                }
            }

            int remainingBits = prefixLength % 8;
            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((targetBytes[fullBytes] & mask) != (baseBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static class StringRule implements Rule {
        private final String raw;

        StringRule(String raw) {
            this.raw = raw;
        }

        @Override
        public boolean matches(InetAddress address, String fallback) {
            if (fallback != null && raw.equalsIgnoreCase(fallback)) {
                return true;
            }
            return address != null && raw.equalsIgnoreCase(address.getHostAddress());
        }
    }
}
