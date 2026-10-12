package net.pilgrim.sgw.asn1;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Represents an ASN.1 BER Tag-Length-Value (TLV) tree node.
 */
public class BerTlv {

    private final BerTag tag;
    private final byte[] valueBytes;
    private final List<BerTlv> children;

    public BerTlv(BerTag tag, byte[] valueBytes) {
        this.tag = Objects.requireNonNull(tag, "tag must not be null");
        this.valueBytes = valueBytes != null ? valueBytes : new byte[0];
        this.children = Collections.emptyList();
    }

    public BerTlv(BerTag tag, List<BerTlv> children) {
        this.tag = Objects.requireNonNull(tag, "tag must not be null");
        this.children = children != null ? new ArrayList<>(children) : Collections.emptyList();
        this.valueBytes = encodeChildren(this.children);
    }

    public BerTag getTag() {
        return tag;
    }

    public boolean isConstructed() {
        return tag.isConstructed();
    }

    public byte[] getValueBytes() {
        return valueBytes;
    }

    public int getLength() {
        return valueBytes.length;
    }

    public List<BerTlv> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public Optional<BerTlv> findChild(int tagNumber) {
        for (BerTlv child : children) {
            if (child.getTag().getTagNumber() == tagNumber) {
                return Optional.of(child);
            }
        }
        return Optional.empty();
    }

    public Optional<BerTlv> findChild(BerTag.TagClass tagClass, int tagNumber) {
        for (BerTlv child : children) {
            if (child.getTag().getTagClass() == tagClass && child.getTag().getTagNumber() == tagNumber) {
                return Optional.of(child);
            }
        }
        return Optional.empty();
    }

    /**
     * Interprets value bytes as an integer.
     */
    public int asInteger() {
        if (valueBytes.length == 0) return 0;
        return new BigInteger(valueBytes).intValue();
    }

    /**
     * Interprets value bytes as a long.
     */
    public long asLong() {
        if (valueBytes.length == 0) return 0L;
        return new BigInteger(valueBytes).longValue();
    }

    /**
     * Interprets value bytes as a boolean.
     */
    public boolean asBoolean() {
        return valueBytes.length > 0 && valueBytes[0] != 0;
    }

    /**
     * Interprets value bytes as a string (UTF-8 / ASCII).
     */
    public String asString() {
        return new String(valueBytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns value bytes formatted as lowercase hex string.
     */
    public String getValueHex() {
        return bytesToHex(valueBytes);
    }

    /**
     * Encodes this entire TLV (tag + length + value) into raw BER bytes.
     */
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tagBytes = tag.encode();
        out.writeBytes(tagBytes);

        byte[] lengthBytes = encodeLength(valueBytes.length);
        out.writeBytes(lengthBytes);

        out.writeBytes(valueBytes);
        return out.toByteArray();
    }

    /**
     * Returns the entire encoded TLV as a hex string.
     */
    public String toHex() {
        return bytesToHex(encode());
    }

    /**
     * Returns the entire encoded TLV as a Base64 string.
     */
    public String toBase64() {
        return Base64.getEncoder().encodeToString(encode());
    }

    /**
     * Converts this TLV node into a structured Map for JSON presentation.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tag", tag.toString());
        map.put("tagClass", tag.getTagClass().name());
        map.put("tagNumber", tag.getTagNumber());
        map.put("constructed", tag.isConstructed());
        map.put("length", valueBytes.length);

        if (tag.isConstructed() && !children.isEmpty()) {
            List<Map<String, Object>> childMaps = new ArrayList<>();
            for (BerTlv child : children) {
                childMaps.add(child.toMap());
            }
            map.put("children", childMaps);
        } else {
            map.put("hexValue", getValueHex());
            if (tag.equals(BerTag.INTEGER)) {
                map.put("integerValue", asLong());
            } else if (tag.equals(BerTag.BOOLEAN)) {
                map.put("booleanValue", asBoolean());
            } else if (tag.equals(BerTag.UTF8_STRING) || tag.equals(BerTag.PRINTABLE_STRING) || tag.equals(BerTag.IA5_STRING)) {
                map.put("stringValue", asString());
            }
        }
        return map;
    }

    private static byte[] encodeChildren(List<BerTlv> children) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (BerTlv child : children) {
            out.writeBytes(child.encode());
        }
        return out.toByteArray();
    }

    public static byte[] encodeLength(int len) {
        if (len < 128) {
            return new byte[]{(byte) len};
        }
        // Long form length
        int temp = len;
        byte[] b = new byte[4];
        int count = 0;
        while (temp > 0) {
            b[count++] = (byte) (temp & 0xFF);
            temp >>>= 8;
        }
        byte[] result = new byte[1 + count];
        result[0] = (byte) (0x80 | count);
        for (int i = 0; i < count; i++) {
            result[1 + i] = b[count - 1 - i];
        }
        return result;
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isBlank()) return new byte[0];
        String clean = hex.replaceAll("[^0-9a-fA-F]", "");
        int len = clean.length();
        if (len % 2 != 0) {
            clean = "0" + clean;
            len++;
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(clean.charAt(i), 16) << 4)
                    + Character.digit(clean.charAt(i + 1), 16));
        }
        return data;
    }

    @Override
    public String toString() {
        return "BerTlv{" +
                "tag=" + tag +
                ", length=" + valueBytes.length +
                (tag.isConstructed() ? ", children=" + children.size() : ", hex=" + getValueHex()) +
                '}';
    }
}
