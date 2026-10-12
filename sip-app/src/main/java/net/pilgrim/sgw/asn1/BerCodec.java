package net.pilgrim.sgw.asn1;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ASN.1 BER (Basic Encoding Rules) Encoder and Decoder (ITU-T X.690).
 */
public final class BerCodec {

    private BerCodec() {}

    /**
     * Decodes the first BER TLV from raw bytes.
     */
    public static BerTlv decode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Cannot decode empty BER data");
        }
        return decode(data, 0).tlv();
    }

    /**
     * Decodes a hex string into a BerTlv.
     */
    public static BerTlv decodeHex(String hex) {
        return decode(BerTlv.hexToBytes(hex));
    }

    /**
     * Decodes a Base64 string into a BerTlv.
     */
    public static BerTlv decodeBase64(String base64) {
        return decode(Base64.getDecoder().decode(base64.trim()));
    }

    /**
     * Decodes all concatenated TLVs in data.
     */
    public static List<BerTlv> decodeAll(byte[] data) {
        if (data == null || data.length == 0) {
            return Collections.emptyList();
        }
        List<BerTlv> list = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            DecodeResult res = decode(data, offset);
            list.add(res.tlv());
            offset += res.bytesConsumed();
        }
        return list;
    }

    /**
     * Internal decoding method returning decoded TLV and bytes consumed.
     */
    public static DecodeResult decode(byte[] data, int offset) {
        if (data == null || offset >= data.length) {
            throw new IllegalArgumentException("Unexpected EOF decoding BER TLV at offset " + offset);
        }

        // 1. Tag
        BerTag.TagResult tagResult = BerTag.decode(data, offset);
        BerTag tag = tagResult.tag();
        int curOffset = offset + tagResult.bytesConsumed();

        // 2. Length
        if (curOffset >= data.length) {
            throw new IllegalArgumentException("Unexpected EOF decoding BER Length at offset " + curOffset);
        }
        int firstLenByte = data[curOffset] & 0xFF;
        curOffset++;

        int length;
        if ((firstLenByte & 0x80) == 0) {
            // Short form: 0..127
            length = firstLenByte;
        } else {
            // Long form
            int numLengthBytes = firstLenByte & 0x7F;
            if (numLengthBytes == 0) {
                throw new UnsupportedOperationException("Indefinite length BER encoding is not supported");
            }
            if (curOffset + numLengthBytes > data.length) {
                throw new IllegalArgumentException("Unexpected EOF reading " + numLengthBytes + " length bytes");
            }
            length = 0;
            for (int i = 0; i < numLengthBytes; i++) {
                length = (length << 8) | (data[curOffset + i] & 0xFF);
            }
            curOffset += numLengthBytes;
        }

        // 3. Value
        if (curOffset + length > data.length) {
            throw new IllegalArgumentException("BER TLV value length " + length + " exceeds buffer size (remaining: " + (data.length - curOffset) + ")");
        }

        int totalConsumed = (curOffset - offset) + length;
        byte[] valueBytes = Arrays.copyOfRange(data, curOffset, curOffset + length);

        if (tag.isConstructed()) {
            List<BerTlv> children = new ArrayList<>();
            int childOffset = 0;
            while (childOffset < valueBytes.length) {
                DecodeResult childRes = decode(valueBytes, childOffset);
                children.add(childRes.tlv());
                childOffset += childRes.bytesConsumed();
            }
            return new DecodeResult(new BerTlv(tag, children), totalConsumed);
        } else {
            return new DecodeResult(new BerTlv(tag, valueBytes), totalConsumed);
        }
    }

    public record DecodeResult(BerTlv tlv, int bytesConsumed) {}

    // ==========================================
    // Fluent TLV Construction Helpers
    // ==========================================

    public static BerTlv integer(int value) {
        return integer(BerTag.INTEGER, value);
    }

    public static BerTlv integer(BerTag tag, int value) {
        byte[] b = BigInteger.valueOf(value).toByteArray();
        return new BerTlv(tag, b);
    }

    public static BerTlv contextInteger(int tagNumber, int value) {
        return integer(BerTag.contextSpecific(tagNumber, false), value);
    }

    public static BerTlv octetString(byte[] bytes) {
        return new BerTlv(BerTag.OCTET_STRING, bytes);
    }

    public static BerTlv octetString(BerTag tag, byte[] bytes) {
        return new BerTlv(tag, bytes);
    }

    public static BerTlv octetString(BerTag tag, String text) {
        return new BerTlv(tag, text != null ? text.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    public static BerTlv contextOctetString(int tagNumber, byte[] bytes) {
        return octetString(BerTag.contextSpecific(tagNumber, false), bytes);
    }

    public static BerTlv contextOctetString(int tagNumber, String text) {
        return octetString(BerTag.contextSpecific(tagNumber, false), text);
    }

    public static BerTlv sequence(BerTlv... children) {
        return new BerTlv(BerTag.SEQUENCE, Arrays.asList(children));
    }

    public static BerTlv sequence(BerTag tag, BerTlv... children) {
        return new BerTlv(tag, Arrays.asList(children));
    }

    public static BerTlv contextConstructed(int tagNumber, BerTlv... children) {
        return new BerTlv(BerTag.contextSpecific(tagNumber, true), Arrays.asList(children));
    }

    public static BerTlv contextPrimitive(int tagNumber, byte[] bytes) {
        return new BerTlv(BerTag.contextSpecific(tagNumber, false), bytes);
    }

    public static BerTlv booleanTlv(boolean value) {
        return new BerTlv(BerTag.BOOLEAN, new byte[]{(byte) (value ? 0xFF : 0x00)});
    }

    public static BerTlv contextBoolean(int tagNumber, boolean value) {
        return new BerTlv(BerTag.contextSpecific(tagNumber, false), new byte[]{(byte) (value ? 0xFF : 0x00)});
    }

    public static BerTlv ia5String(String text) {
        return new BerTlv(BerTag.IA5_STRING, text != null ? text.getBytes(StandardCharsets.US_ASCII) : new byte[0]);
    }

    public static BerTlv contextIa5String(int tagNumber, String text) {
        return new BerTlv(BerTag.contextSpecific(tagNumber, false), text != null ? text.getBytes(StandardCharsets.US_ASCII) : new byte[0]);
    }
}
