package net.pilgrim.msml.model.moml;

import java.util.regex.Pattern;

/**
 * Grammar pattern specifying DTMF collection parameters (RFC 5707 Section 4.4).
 */
public record DtmfPattern(int minDigits, int maxDigits, String returnKey, String cancelKey) {

    public DtmfPattern {
        if (minDigits < 0) minDigits = 1;
        if (maxDigits < minDigits) maxDigits = minDigits;
    }

    public static DtmfPattern ofSingleDigit() {
        return new DtmfPattern(1, 1, "#", "*");
    }

    public static DtmfPattern ofRange(int min, int max, String returnKey) {
        return new DtmfPattern(min, max, returnKey, null);
    }

    /**
     * Parses RFC 5707 digits format: e.g. {@code "min=1;max=4;rtk=#;ctk=*"}.
     */
    public static DtmfPattern parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ofSingleDigit();
        }
        int min = 1;
        int max = 1;
        String rtk = "#";
        String ctk = null;

        String[] pairs = raw.split(";");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                String k = kv[0].trim().toLowerCase();
                String v = kv[1].trim();
                switch (k) {
                    case "min" -> {
                        try { min = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                    }
                    case "max" -> {
                        try { max = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                    }
                    case "rtk" -> rtk = v;
                    case "ctk" -> ctk = v;
                }
            }
        }
        return new DtmfPattern(min, Math.max(min, max), rtk, ctk);
    }
}
