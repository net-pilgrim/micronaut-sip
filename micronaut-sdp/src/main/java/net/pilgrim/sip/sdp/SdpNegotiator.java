package net.pilgrim.sip.sdp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Basic SDP offer/answer helper.
 */
public final class SdpNegotiator {

    private static final Set<String> DIRECTION_ATTRIBUTES = Set.of("sendrecv", "sendonly", "recvonly", "inactive");

    private final SdpParser parser = new SdpParser();
    private final String localAddress;
    private final int localAudioPort;
    private final String originUsername;
    private final String sessionName;
    private final String mediaProtocol;
    private final List<String> supportedPayloadTypes;
    private final Map<String, String> defaultRtpMaps;

    public SdpNegotiator() {
        this("127.0.0.1", 49170, "MicronautSIP", "Call", "RTP/AVP",
                List.of("0", "8"),
                Map.of(
                        "0", "PCMU/8000",
                        "8", "PCMA/8000",
                        "96", "opus/48000/2"
                ));
    }

    public SdpNegotiator(String localAddress,
                         int localAudioPort,
                         String originUsername,
                         String sessionName,
                         String mediaProtocol,
                         List<String> supportedPayloadTypes,
                         Map<String, String> defaultRtpMaps) {
        this.localAddress = (localAddress == null || localAddress.isBlank()) ? "127.0.0.1" : localAddress.trim();
        this.localAudioPort = localAudioPort > 0 ? localAudioPort : 49170;
        this.originUsername = (originUsername == null || originUsername.isBlank()) ? "MicronautSIP" : originUsername.trim();
        this.sessionName = (sessionName == null || sessionName.isBlank()) ? "Call" : sessionName.trim();
        this.mediaProtocol = (mediaProtocol == null || mediaProtocol.isBlank()) ? "RTP/AVP" : mediaProtocol.trim();
        this.supportedPayloadTypes = supportedPayloadTypes == null || supportedPayloadTypes.isEmpty()
                ? List.of("0")
                : List.copyOf(supportedPayloadTypes);
        this.defaultRtpMaps = defaultRtpMaps == null ? Map.of() : new LinkedHashMap<>(defaultRtpMaps);
    }

    public SdpMessage parse(String sdpText) {
        return parser.parse(sdpText);
    }

    public String createOffer() {
        return createOffer(this.localAudioPort);
    }

    public String createOffer(int localPort) {
        SdpMessage offer = createBaseLocalMessage();
        SdpMessage.MediaDescription media = new SdpMessage.MediaDescription(
                "audio",
                localPort > 0 ? localPort : localAudioPort,
                mediaProtocol,
                supportedPayloadTypes
        );
        for (String payloadType : supportedPayloadTypes) {
            String rtpMap = defaultRtpMaps.get(payloadType);
            if (rtpMap != null) {
                media.addAttribute("rtpmap:" + payloadType + " " + rtpMap);
            }
        }
        media.addAttribute("sendrecv");
        offer.addMediaDescription(media);
        return offer.toSdpString();
    }

    public String createAnswer(String offerSdp) {
        return createAnswer(parse(offerSdp), this.localAudioPort);
    }

    public String createAnswer(String offerSdp, int localPort) {
        return createAnswer(parse(offerSdp), localPort);
    }

    public String createAnswer(SdpMessage offer) {
        return createAnswer(offer, this.localAudioPort);
    }

    public String createAnswer(SdpMessage offer, int localPort) {
        if (offer == null) {
            return createOffer(localPort);
        }

        SdpMessage answer = createBaseLocalMessage();
        SdpMessage.MediaDescription offeredAudio = offer.findFirstAudioMedia();
        if (offeredAudio == null) {
            return createOffer(localPort);
        }

        List<String> acceptedPayloads = negotiatePayloadTypes(offeredAudio.getFormats());
        if (acceptedPayloads.isEmpty()) {
            acceptedPayloads = List.of("0");
        }

        SdpMessage.MediaDescription mediaAnswer = new SdpMessage.MediaDescription(
                "audio",
                localPort > 0 ? localPort : localAudioPort,
                offeredAudio.getProtocol() == null || offeredAudio.getProtocol().isBlank() ? mediaProtocol : offeredAudio.getProtocol(),
                acceptedPayloads
        );

        Map<String, String> offeredRtpMaps = parseRtpMaps(offeredAudio.getAttributes());
        for (String payloadType : acceptedPayloads) {
            String rtpMap = offeredRtpMaps.get(payloadType);
            if (rtpMap == null) {
                rtpMap = defaultRtpMaps.get(payloadType);
            }
            if (rtpMap != null) {
                mediaAnswer.addAttribute("rtpmap:" + payloadType + " " + rtpMap);
            }
        }

        mediaAnswer.addAttribute(resolveAnswerDirection(offer, offeredAudio));
        answer.addMediaDescription(mediaAnswer);
        return answer.toSdpString();
    }

    private SdpMessage createBaseLocalMessage() {
        SdpMessage message = new SdpMessage();
        message.setVersion("0");
        message.setOrigin(originUsername + " 2890844526 2890844526 IN IP4 " + localAddress);
        message.setSessionName(sessionName);
        message.setConnection("IN IP4 " + localAddress);
        message.setTiming("0 0");
        return message;
    }

    private List<String> negotiatePayloadTypes(List<String> offeredPayloadTypes) {
        if (offeredPayloadTypes == null || offeredPayloadTypes.isEmpty()) {
            return new ArrayList<>(supportedPayloadTypes);
        }
        List<String> accepted = new ArrayList<>();
        for (String payload : offeredPayloadTypes) {
            if (supportedPayloadTypes.contains(payload)) {
                accepted.add(payload);
            }
        }
        if (accepted.isEmpty()) {
            accepted.add(offeredPayloadTypes.get(0));
        }
        return accepted;
    }

    private String resolveAnswerDirection(SdpMessage offer, SdpMessage.MediaDescription offeredAudio) {
        String offerDirection = offeredAudio.getDirectionAttribute();
        if (offerDirection == null) {
            offerDirection = offer.getDirectionAttribute();
        }
        if (offerDirection == null) {
            offerDirection = "sendrecv";
        }

        return switch (offerDirection.toLowerCase(Locale.ROOT)) {
            case "sendonly" -> "recvonly";
            case "recvonly" -> "sendonly";
            case "inactive" -> "inactive";
            default -> "sendrecv";
        };
    }

    private Map<String, String> parseRtpMaps(List<String> attributes) {
        Map<String, String> result = new LinkedHashMap<>();
        if (attributes == null) {
            return result;
        }
        for (String attr : attributes) {
            if (attr == null || attr.isBlank()) {
                continue;
            }
            String lower = attr.toLowerCase(Locale.ROOT);
            if (DIRECTION_ATTRIBUTES.contains(lower)) {
                continue;
            }
            if (!lower.startsWith("rtpmap:")) {
                continue;
            }
            int space = attr.indexOf(' ');
            if (space <= "rtpmap:".length()) {
                continue;
            }
            String pt = attr.substring("rtpmap:".length(), space).trim();
            String mapping = attr.substring(space + 1).trim();
            if (!pt.isEmpty() && !mapping.isEmpty()) {
                result.put(pt, mapping);
            }
        }
        return result;
    }
}
