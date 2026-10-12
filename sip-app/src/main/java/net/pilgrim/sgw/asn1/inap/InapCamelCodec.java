package net.pilgrim.sgw.asn1.inap;

import net.pilgrim.sgw.asn1.BerCodec;
import net.pilgrim.sgw.asn1.BerTag;
import net.pilgrim.sgw.asn1.BerTlv;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * ASN.1 BER Codec for INAP (ITU-T Q.1218 / Q.1228) and CAMEL (3GPP TS 29.078 / GSM 09.78) operations.
 */
public final class InapCamelCodec {

    // CAMEL & INAP Standard Operation Codes (opcodes)
    public static final int OP_INITIAL_DP = 0;
    public static final int OP_CONNECT = 20;
    public static final int OP_RELEASE_CALL = 22;
    public static final int OP_REQUEST_REPORT_BCSM = 23;
    public static final int OP_EVENT_REPORT_BCSM = 24;
    public static final int OP_CONTINUE = 31;
    public static final int OP_FURNISH_CHARGING_INFO = 34;
    public static final int OP_APPLY_CHARGING = 35;
    public static final int OP_APPLY_CHARGING_REPORT = 36;
    public static final int OP_PLAY_ANNOUNCEMENT = 47;
    public static final int OP_PROMPT_AND_COLLECT_USER_INFORMATION = 48;
    public static final int OP_SPECIALIZED_RESOURCE_REPORT = 49;

    private InapCamelCodec() {}

    /**
     * Encodes a CAMEL InitialDP argument into an ASN.1 BER SEQUENCE.
     *
     * <pre>
     * InitialDPArg ::= SEQUENCE {
     *   serviceKey           [0] INTEGER,
     *   calledPartyNumber    [2] OCTET STRING OPTIONAL,
     *   callingPartyNumber   [3] OCTET STRING OPTIONAL
     * }
     * </pre>
     */
    public static BerTlv encodeInitialDP(int serviceKey, String calledPartyNumber, String callingPartyNumber) {
        List<BerTlv> fields = new ArrayList<>();

        // serviceKey [0] INTEGER
        fields.add(BerCodec.contextInteger(0, serviceKey));

        // calledPartyNumber [2] OCTET STRING
        if (calledPartyNumber != null && !calledPartyNumber.isBlank()) {
            byte[] bcd = encodeTbcdNumber(calledPartyNumber, 0x84, 0x10);
            fields.add(BerCodec.contextOctetString(2, bcd));
        }

        // callingPartyNumber [3] OCTET STRING
        if (callingPartyNumber != null && !callingPartyNumber.isBlank()) {
            byte[] bcd = encodeTbcdNumber(callingPartyNumber, 0x84, 0x10);
            fields.add(BerCodec.contextOctetString(3, bcd));
        }

        return BerCodec.sequence(fields.toArray(new BerTlv[0]));
    }

    /**
     * Decodes an InitialDP BER TLV.
     */
    public static InitialDPData decodeInitialDP(BerTlv tlv) {
        if (tlv == null) return new InitialDPData(0, null, null);

        int serviceKey = 0;
        String calledParty = null;
        String callingParty = null;

        for (BerTlv child : tlv.getChildren()) {
            int tagNum = child.getTag().getTagNumber();
            if (tagNum == 0) {
                serviceKey = child.asInteger();
            } else if (tagNum == 2) {
                calledParty = decodeTbcdNumber(child.getValueBytes());
            } else if (tagNum == 3) {
                callingParty = decodeTbcdNumber(child.getValueBytes());
            }
        }

        return new InitialDPData(serviceKey, calledParty, callingParty);
    }

    public record InitialDPData(int serviceKey, String calledPartyNumber, String callingPartyNumber) {}

    /**
     * Encodes a CAMEL Connect argument.
     *
     * <pre>
     * ConnectArg ::= SEQUENCE {
     *   destinationRoutingAddress [0] SEQUENCE OF CalledPartyNumber
     * }
     * </pre>
     */
    public static BerTlv encodeConnect(String destinationRoutingAddress) {
        byte[] bcd = encodeTbcdNumber(destinationRoutingAddress, 0x84, 0x10);
        BerTlv destTlv = BerCodec.octetString(bcd);
        BerTlv seqOf = BerCodec.contextConstructed(0, destTlv);
        return BerCodec.sequence(seqOf);
    }

    /**
     * Decodes a Connect BER TLV, extracting the destinationRoutingAddress.
     */
    public static String decodeConnect(BerTlv tlv) {
        if (tlv == null) return null;
        Optional<BerTlv> destSeqOpt = tlv.findChild(0);
        if (destSeqOpt.isPresent() && !destSeqOpt.get().getChildren().isEmpty()) {
            return decodeTbcdNumber(destSeqOpt.get().getChildren().get(0).getValueBytes());
        }
        return null;
    }

    /**
     * Encodes a CAMEL ApplyCharging argument.
     *
     * <pre>
     * ApplyChargingArg ::= SEQUENCE {
     *   aChBillingChargingCharacteristics [0] OCTET STRING
     * }
     * </pre>
     */
    public static BerTlv encodeApplyCharging(int maxCallPeriodDurationSeconds) {
        // Encapsulate time duration integer in sub-sequence
        BerTlv durationTlv = BerCodec.contextInteger(0, maxCallPeriodDurationSeconds);
        BerTlv charTlv = BerCodec.contextConstructed(0, durationTlv);
        return BerCodec.sequence(charTlv);
    }

    /**
     * Decodes an ApplyCharging BER TLV.
     */
    public static int decodeApplyCharging(BerTlv tlv) {
        if (tlv == null) return 0;
        Optional<BerTlv> charOpt = tlv.findChild(0);
        if (charOpt.isPresent()) {
            Optional<BerTlv> durOpt = charOpt.get().findChild(0);
            if (durOpt.isPresent()) {
                return durOpt.get().asInteger();
            }
        }
        return 0;
    }

    /**
     * Encodes a CAMEL ReleaseCall argument with a Q.850 cause code.
     *
     * <pre>
     * ReleaseCallArg ::= Cause (OCTET STRING)
     * </pre>
     */
    public static BerTlv encodeReleaseCall(int causeCode) {
        // Standard Q.850 cause: byte 1 = coding standard/location (0x80), byte 2 = cause with bit 8 = 1
        byte[] causeBytes = new byte[]{(byte) 0x80, (byte) (0x80 | (causeCode & 0x7F))};
        return BerCodec.octetString(causeBytes);
    }

    /**
     * Encodes a CAMEL / INAP Continue operation (empty sequence).
     */
    public static BerTlv encodeContinue() {
        return BerCodec.sequence();
    }

    // ==========================================
    // PlayAnnouncement (ITU-T Q.1218 / 3GPP TS 29.078 Opcode 47)
    // ==========================================

    public record PlayAnnouncementData(
            int messageId,
            String announcementText,
            int numberOfLoops,
            int duration,
            boolean disconnectFromIpForbidden,
            boolean requestAnnouncementComplete
    ) {}

    /**
     * Encodes PlayAnnouncement argument into an ASN.1 BER SEQUENCE.
     *
     * <pre>
     * PlayAnnouncementArg ::= SEQUENCE {
     *   informationToSend                       [0] InformationToSend,
     *   disconnectFromIPForbidden               [1] BOOLEAN DEFAULT TRUE,
     *   requestAnnouncementCompleteNotification [2] BOOLEAN DEFAULT TRUE
     * }
     * </pre>
     */
    public static BerTlv encodePlayAnnouncement(int messageId, int numberOfLoops, boolean requestAnnouncementComplete) {
        return encodePlayAnnouncement(messageId, null, numberOfLoops, null, true, requestAnnouncementComplete);
    }

    public static BerTlv encodePlayAnnouncement(Integer messageId, String announcementText, int numberOfLoops, Integer duration,
                                                boolean disconnectFromIpForbidden, boolean requestAnnouncementComplete) {
        List<BerTlv> inbandChildren = new ArrayList<>();

        // messageID [0] CHOICE: elementaryMessageID [0] INTEGER or text [1] IA5String
        BerTlv msgIdChoice;
        if (announcementText != null && !announcementText.isBlank()) {
            msgIdChoice = BerCodec.contextIa5String(1, announcementText);
        } else {
            msgIdChoice = BerCodec.contextInteger(0, messageId != null ? messageId : 1);
        }
        inbandChildren.add(BerCodec.contextConstructed(0, msgIdChoice));

        // numberOfLoops [1] INTEGER
        if (numberOfLoops > 0) {
            inbandChildren.add(BerCodec.contextInteger(1, numberOfLoops));
        }

        // duration [2] INTEGER
        if (duration != null && duration > 0) {
            inbandChildren.add(BerCodec.contextInteger(2, duration));
        }

        BerTlv inbandInfo = BerCodec.contextConstructed(0, inbandChildren.toArray(new BerTlv[0]));
        BerTlv infoToSend = BerCodec.contextConstructed(0, inbandInfo);

        List<BerTlv> argChildren = new ArrayList<>();
        argChildren.add(infoToSend);
        argChildren.add(BerCodec.contextBoolean(1, disconnectFromIpForbidden));
        argChildren.add(BerCodec.contextBoolean(2, requestAnnouncementComplete));

        return BerCodec.sequence(argChildren.toArray(new BerTlv[0]));
    }

    /**
     * Decodes a PlayAnnouncement BER TLV.
     */
    public static PlayAnnouncementData decodePlayAnnouncement(BerTlv tlv) {
        if (tlv == null) return new PlayAnnouncementData(0, null, 1, 0, true, true);

        int messageId = 0;
        String announcementText = null;
        int numberOfLoops = 1;
        int duration = 0;
        boolean disconnectForbidden = true;
        boolean requestComplete = true;

        for (BerTlv child : tlv.getChildren()) {
            int tag = child.getTag().getTagNumber();
            if (tag == 0) { // informationToSend [0]
                Optional<BerTlv> inbandOpt = child.findChild(0); // inbandInfo [0]
                if (inbandOpt.isPresent()) {
                    BerTlv inband = inbandOpt.get();
                    // messageID [0]
                    Optional<BerTlv> msgIdOpt = inband.findChild(0);
                    if (msgIdOpt.isPresent()) {
                        BerTlv msgIdNode = msgIdOpt.get();
                        if (msgIdNode.isConstructed() && !msgIdNode.getChildren().isEmpty()) {
                            BerTlv choice = msgIdNode.getChildren().get(0);
                            if (choice.getTag().getTagNumber() == 1) {
                                announcementText = choice.asString();
                            } else {
                                messageId = choice.asInteger();
                            }
                        } else {
                            messageId = msgIdNode.asInteger();
                        }
                    }
                    Optional<BerTlv> loopsOpt = inband.findChild(1);
                    if (loopsOpt.isPresent()) numberOfLoops = loopsOpt.get().asInteger();
                    Optional<BerTlv> durOpt = inband.findChild(2);
                    if (durOpt.isPresent()) duration = durOpt.get().asInteger();
                }
            } else if (tag == 1) { // disconnectFromIPForbidden [1]
                disconnectForbidden = child.asBoolean();
            } else if (tag == 2) { // requestAnnouncementCompleteNotification [2]
                requestComplete = child.asBoolean();
            }
        }

        return new PlayAnnouncementData(messageId, announcementText, numberOfLoops, duration, disconnectForbidden, requestComplete);
    }

    // ==========================================
    // PromptAndCollectUserInformation / playAndCollectUserInfo (Opcode 48)
    // ==========================================

    public record PromptAndCollectData(
            int messageId,
            String announcementText,
            int minDigits,
            int maxDigits,
            String endOfReplyDigit,
            String cancelDigit,
            int firstDigitTimeout,
            int interDigitTimeout,
            boolean interruptable,
            boolean disconnectFromIpForbidden
    ) {}

    /**
     * Encodes PromptAndCollectUserInformation (playAndCollectUserInfo) argument into an ASN.1 BER SEQUENCE.
     *
     * <pre>
     * PromptAndCollectUserInformationArg ::= SEQUENCE {
     *   collectedInfo             [0] CollectedInfo,
     *   disconnectFromIPForbidden [1] BOOLEAN DEFAULT TRUE,
     *   informationToSend         [2] InformationToSend OPTIONAL
     * }
     * </pre>
     */
    public static BerTlv encodePromptAndCollectUserInfo(int messageId, int minDigits, int maxDigits, String endOfReplyDigit) {
        return encodePromptAndCollectUserInfo(messageId, null, minDigits, maxDigits, endOfReplyDigit, "*", 10, 5, true, true);
    }

    public static BerTlv encodePromptAndCollectUserInfo(
            Integer messageId,
            String announcementText,
            int minDigits,
            int maxDigits,
            String endOfReplyDigit,
            String cancelDigit,
            int firstDigitTimeout,
            int interDigitTimeout,
            boolean interruptable,
            boolean disconnectFromIpForbidden) {

        List<BerTlv> digitChildren = new ArrayList<>();
        digitChildren.add(BerCodec.contextInteger(0, Math.max(1, minDigits)));
        digitChildren.add(BerCodec.contextInteger(1, Math.max(1, maxDigits)));
        if (endOfReplyDigit != null && !endOfReplyDigit.isEmpty()) {
            digitChildren.add(BerCodec.contextIa5String(2, endOfReplyDigit));
        }
        if (cancelDigit != null && !cancelDigit.isEmpty()) {
            digitChildren.add(BerCodec.contextIa5String(3, cancelDigit));
        }
        if (firstDigitTimeout > 0) {
            digitChildren.add(BerCodec.contextInteger(5, firstDigitTimeout));
        }
        if (interDigitTimeout > 0) {
            digitChildren.add(BerCodec.contextInteger(6, interDigitTimeout));
        }
        digitChildren.add(BerCodec.contextBoolean(8, interruptable));

        BerTlv collectedDigits = BerCodec.contextConstructed(0, digitChildren.toArray(new BerTlv[0]));
        BerTlv collectedInfo = BerCodec.contextConstructed(0, collectedDigits);

        List<BerTlv> argChildren = new ArrayList<>();
        argChildren.add(collectedInfo);
        argChildren.add(BerCodec.contextBoolean(1, disconnectFromIpForbidden));

        // Optional InformationToSend [2]
        if ((messageId != null && messageId > 0) || (announcementText != null && !announcementText.isBlank())) {
            List<BerTlv> inbandChildren = new ArrayList<>();
            BerTlv msgIdChoice;
            if (announcementText != null && !announcementText.isBlank()) {
                msgIdChoice = BerCodec.contextIa5String(1, announcementText);
            } else {
                msgIdChoice = BerCodec.contextInteger(0, messageId);
            }
            inbandChildren.add(BerCodec.contextConstructed(0, msgIdChoice));
            BerTlv inbandInfo = BerCodec.contextConstructed(0, inbandChildren.toArray(new BerTlv[0]));
            BerTlv infoToSend = BerCodec.contextConstructed(2, inbandInfo);
            argChildren.add(infoToSend);
        }

        return BerCodec.sequence(argChildren.toArray(new BerTlv[0]));
    }

    /**
     * Decodes a PromptAndCollectUserInformation (playAndCollectUserInfo) BER TLV.
     */
    public static PromptAndCollectData decodePromptAndCollectUserInfo(BerTlv tlv) {
        if (tlv == null) return new PromptAndCollectData(0, null, 1, 1, "#", "*", 0, 0, true, true);

        int messageId = 0;
        String announcementText = null;
        int minDigits = 1;
        int maxDigits = 1;
        String endOfReplyDigit = "#";
        String cancelDigit = "*";
        int firstDigitTimeout = 0;
        int interDigitTimeout = 0;
        boolean interruptable = true;
        boolean disconnectForbidden = true;

        for (BerTlv child : tlv.getChildren()) {
            int tag = child.getTag().getTagNumber();
            if (tag == 0) { // collectedInfo [0]
                Optional<BerTlv> cdOpt = child.findChild(0); // collectedDigits [0]
                if (cdOpt.isPresent()) {
                    BerTlv cd = cdOpt.get();
                    Optional<BerTlv> minOpt = cd.findChild(0);
                    if (minOpt.isPresent()) minDigits = minOpt.get().asInteger();
                    Optional<BerTlv> maxOpt = cd.findChild(1);
                    if (maxOpt.isPresent()) maxDigits = maxOpt.get().asInteger();
                    Optional<BerTlv> eorOpt = cd.findChild(2);
                    if (eorOpt.isPresent()) endOfReplyDigit = eorOpt.get().asString();
                    Optional<BerTlv> canOpt = cd.findChild(3);
                    if (canOpt.isPresent()) cancelDigit = canOpt.get().asString();
                    Optional<BerTlv> ftoOpt = cd.findChild(5);
                    if (ftoOpt.isPresent()) firstDigitTimeout = ftoOpt.get().asInteger();
                    Optional<BerTlv> itoOpt = cd.findChild(6);
                    if (itoOpt.isPresent()) interDigitTimeout = itoOpt.get().asInteger();
                    Optional<BerTlv> intrOpt = cd.findChild(8);
                    if (intrOpt.isPresent()) interruptable = intrOpt.get().asBoolean();
                }
            } else if (tag == 1) { // disconnectFromIPForbidden [1]
                disconnectForbidden = child.asBoolean();
            } else if (tag == 2) { // informationToSend [2]
                Optional<BerTlv> inbandOpt = child.findChild(0); // inbandInfo [0]
                if (inbandOpt.isPresent()) {
                    Optional<BerTlv> msgIdOpt = inbandOpt.get().findChild(0);
                    if (msgIdOpt.isPresent()) {
                        BerTlv msgIdNode = msgIdOpt.get();
                        if (msgIdNode.isConstructed() && !msgIdNode.getChildren().isEmpty()) {
                            BerTlv choice = msgIdNode.getChildren().get(0);
                            if (choice.getTag().getTagNumber() == 1) {
                                announcementText = choice.asString();
                            } else {
                                messageId = choice.asInteger();
                            }
                        } else {
                            messageId = msgIdNode.asInteger();
                        }
                    }
                }
            }
        }

        return new PromptAndCollectData(messageId, announcementText, minDigits, maxDigits, endOfReplyDigit, cancelDigit, firstDigitTimeout, interDigitTimeout, interruptable, disconnectForbidden);
    }

    /**
     * Encodes a collected digits response into an ASN.1 BER TLV (ReceivedInformationArg).
     */
    public static BerTlv encodeDigitsResponse(String digits) {
        return BerCodec.contextIa5String(0, digits != null ? digits : "");
    }

    /**
     * Decodes collected digits from a ReturnResult BER TLV.
     */
    public static String decodeDigitsResponse(BerTlv tlv) {
        if (tlv == null) return "";
        if (tlv.getTag().getTagNumber() == 0) {
            return tlv.asString();
        }
        Optional<BerTlv> childOpt = tlv.findChild(0);
        if (childOpt.isPresent()) {
            return childOpt.get().asString();
        }
        return tlv.asString();
    }

    /**
     * Encodes a SpecializedResourceReport (SRR) operation.
     */
    public static BerTlv encodeSpecializedResourceReport() {
        return BerCodec.sequence();
    }

    // ==========================================
    // Telephony BCD Number Encoding Helpers
    // ==========================================

    /**
     * Encodes a phone number string into ITU-T / CAMEL BCD octets with nature of address and numbering plan headers.
     */
    public static byte[] encodeTbcdNumber(String number, int natureOfAddress, int numberingPlan) {
        if (number == null) return new byte[0];
        String digits = number.replaceAll("[^0-9]", "");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean odd = (digits.length() % 2 != 0);
        int noaByte = (natureOfAddress & 0x7F) | (odd ? 0x80 : 0x00);
        out.write(noaByte);
        out.write(numberingPlan & 0xFF);

        for (int i = 0; i < digits.length(); i += 2) {
            int low = Character.digit(digits.charAt(i), 10);
            int high = (i + 1 < digits.length()) ? Character.digit(digits.charAt(i + 1), 10) : 0x0F;
            out.write((high << 4) | (low & 0x0F));
        }
        return out.toByteArray();
    }

    /**
     * Decodes ITU-T / CAMEL BCD octets back into a normalized digits string.
     */
    public static String decodeTbcdNumber(byte[] bytes) {
        if (bytes == null || bytes.length < 2) return "";
        StringBuilder sb = new StringBuilder();
        // Bytes 0 and 1 are Nature of Address and Numbering Plan
        for (int i = 2; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            int low = b & 0x0F;
            int high = (b >>> 4) & 0x0F;

            if (low < 10) {
                sb.append(low);
            }
            if (high < 10) {
                sb.append(high);
            }
        }
        return sb.toString();
    }
}
