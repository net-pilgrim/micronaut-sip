package net.pilgrim.sgw.asn1;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * Represents an ASN.1 BER Tag (ITU-T X.690 Section 8.1.2).
 * Supports Universal, Application, Context-Specific, and Private classes,
 * primitive vs constructed forms, and multi-octet tag numbers.
 */
public final class BerTag {

    public enum TagClass {
        UNIVERSAL(0x00),
        APPLICATION(0x40),
        CONTEXT_SPECIFIC(0x80),
        PRIVATE(0xC0);

        private final int mask;

        TagClass(int mask) {
            this.mask = mask;
        }

        public int getMask() {
            return mask;
        }

        public static TagClass fromByte(int b) {
            int masked = b & 0xC0;
            for (TagClass tc : values()) {
                if (tc.mask == masked) {
                    return tc;
                }
            }
            return UNIVERSAL;
        }
    }

    // Standard Universal Tags (ITU-T X.680)
    public static final BerTag BOOLEAN = universal(1, false);
    public static final BerTag INTEGER = universal(2, false);
    public static final BerTag BIT_STRING = universal(3, false);
    public static final BerTag OCTET_STRING = universal(4, false);
    public static final BerTag NULL = universal(5, false);
    public static final BerTag OID = universal(6, false);
    public static final BerTag ENUMERATED = universal(10, false);
    public static final BerTag UTF8_STRING = universal(12, false);
    public static final BerTag SEQUENCE = universal(16, true);
    public static final BerTag SET = universal(17, true);
    public static final BerTag PRINTABLE_STRING = universal(19, false);
    public static final BerTag IA5_STRING = universal(22, false);

    private final TagClass tagClass;
    private final boolean constructed;
    private final int tagNumber;

    public BerTag(TagClass tagClass, boolean constructed, int tagNumber) {
        this.tagClass = Objects.requireNonNull(tagClass, "tagClass must not be null");
        this.constructed = constructed;
        this.tagNumber = tagNumber;
    }

    public static BerTag universal(int tagNumber, boolean constructed) {
        return new BerTag(TagClass.UNIVERSAL, constructed, tagNumber);
    }

    public static BerTag contextSpecific(int tagNumber, boolean constructed) {
        return new BerTag(TagClass.CONTEXT_SPECIFIC, constructed, tagNumber);
    }

    public static BerTag application(int tagNumber, boolean constructed) {
        return new BerTag(TagClass.APPLICATION, constructed, tagNumber);
    }

    public static BerTag privateTag(int tagNumber, boolean constructed) {
        return new BerTag(TagClass.PRIVATE, constructed, tagNumber);
    }

    public TagClass getTagClass() {
        return tagClass;
    }

    public boolean isConstructed() {
        return constructed;
    }

    public int getTagNumber() {
        return tagNumber;
    }

    /**
     * Encodes this tag into BER octets.
     */
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int firstByte = tagClass.getMask() | (constructed ? 0x20 : 0x00);

        if (tagNumber < 31) {
            firstByte |= tagNumber;
            out.write(firstByte);
        } else {
            firstByte |= 0x1F;
            out.write(firstByte);

            // Multi-octet base-128 tag encoding
            int val = tagNumber;
            int[] octets = new int[5];
            int count = 0;
            while (val > 0) {
                octets[count++] = val & 0x7F;
                val >>>= 7;
            }
            for (int i = count - 1; i >= 0; i--) {
                int octet = octets[i];
                if (i > 0) {
                    octet |= 0x80; // more octets follow
                }
                out.write(octet);
            }
        }
        return out.toByteArray();
    }

    /**
     * Reads a BER Tag from a byte buffer.
     * Returns a TagResult with the parsed BerTag and number of bytes consumed.
     */
    public static TagResult decode(byte[] data, int offset) {
        if (data == null || offset >= data.length) {
            throw new IllegalArgumentException("Unexpected EOF decoding BER Tag");
        }

        int first = data[offset] & 0xFF;
        TagClass tc = TagClass.fromByte(first);
        boolean constructed = (first & 0x20) != 0;
        int tagNum = first & 0x1F;
        int consumed = 1;

        if (tagNum == 0x1F) {
            // Multi-octet tag
            tagNum = 0;
            while (true) {
                if (offset + consumed >= data.length) {
                    throw new IllegalArgumentException("Unexpected EOF decoding multi-octet BER Tag");
                }
                int b = data[offset + consumed] & 0xFF;
                consumed++;
                tagNum = (tagNum << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) {
                    break;
                }
            }
        }

        return new TagResult(new BerTag(tc, constructed, tagNum), consumed);
    }

    public record TagResult(BerTag tag, int bytesConsumed) {}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BerTag berTag)) return false;
        return constructed == berTag.constructed &&
                tagNumber == berTag.tagNumber &&
                tagClass == berTag.tagClass;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tagClass, constructed, tagNumber);
    }

    @Override
    public String toString() {
        String type = constructed ? "Constructed" : "Primitive";
        return "[" + tagClass.name() + " " + tagNumber + " " + type + "]";
    }
}
