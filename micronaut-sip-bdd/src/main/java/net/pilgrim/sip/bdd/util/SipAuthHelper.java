package net.pilgrim.sip.bdd.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for parsing SIP digest challenges (RFC 2617 / RFC 3261) and generating Authorization headers.
 */
public final class SipAuthHelper {

    private static final Pattern PARAM_PATTERN = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))");

    private SipAuthHelper() {}

    /**
     * Parses key-value parameters from a Digest challenge or Authorization header.
     */
    public static Map<String, String> parseDigestParameters(String headerValue) {
        Map<String, String> params = new HashMap<>();
        if (headerValue == null) {
            return params;
        }

        String raw = headerValue.trim();
        if (raw.toLowerCase().startsWith("digest ")) {
            raw = raw.substring(7).trim();
        }

        Matcher matcher = PARAM_PATTERN.matcher(raw);
        while (matcher.find()) {
            String key = matcher.group(1);
            String valQuoted = matcher.group(2);
            String valUnquoted = matcher.group(3);
            params.put(key.toLowerCase(), valQuoted != null ? valQuoted : valUnquoted);
        }
        return params;
    }

    /**
     * Calculates the digest response string according to RFC 2617.
     */
    public static String calculateResponse(String username, String realm, String password,
                                           String method, String uri, String nonce,
                                           String nc, String cnonce, String qop) {
        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);

        if (qop != null && !qop.isBlank()) {
            return md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
        } else {
            return md5(ha1 + ":" + nonce + ":" + ha2);
        }
    }

    /**
     * Builds a full Authorization header value from credentials and challenge.
     */
    public static String buildAuthorizationHeader(String username, String password,
                                                  String method, String uri,
                                                  String challengeHeader) {
        Map<String, String> challenge = parseDigestParameters(challengeHeader);
        String realm = challenge.getOrDefault("realm", "");
        String nonce = challenge.getOrDefault("nonce", "");
        String opaque = challenge.get("opaque");
        String qop = challenge.get("qop");

        String nc = "00000001";
        String cnonce = "0a4f113b";

        String effectiveQop = null;
        if (qop != null && qop.contains("auth")) {
            effectiveQop = "auth";
        }

        String response = calculateResponse(username, realm, password, method, uri, nonce, nc, cnonce, effectiveQop);

        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(username).append("\", ");
        sb.append("realm=\"").append(realm).append("\", ");
        sb.append("nonce=\"").append(nonce).append("\", ");
        sb.append("uri=\"").append(uri).append("\", ");
        sb.append("response=\"").append(response).append("\"");

        if (effectiveQop != null) {
            sb.append(", qop=").append(effectiveQop);
            sb.append(", nc=").append(nc);
            sb.append(", cnonce=\"").append(cnonce).append("\"");
        }
        if (opaque != null) {
            sb.append(", opaque=\"").append(opaque).append("\"");
        }
        return sb.toString();
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
