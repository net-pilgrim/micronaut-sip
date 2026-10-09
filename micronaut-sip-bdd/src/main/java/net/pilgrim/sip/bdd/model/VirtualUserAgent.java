package net.pilgrim.sip.bdd.model;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import net.pilgrim.sip.bdd.util.SipAuthHelper;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.parser.SipEncoder;
import net.pilgrim.sip.parser.SipParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Virtual User Agent (VUA) representing a participant in a SIP BDD scenario.
 * Encapsulates its own network channel, mailbox, credentials, and dialog state.
 */
public class VirtualUserAgent implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(VirtualUserAgent.class);

    private final String name;
    private final String sipUri;
    private final InetSocketAddress boundAddress;
    private final SipTransport transport;
    private final SipMailbox mailbox;
    private final ScenarioContext context;
    private final Channel channel;
    private final EventLoopGroup eventLoopGroup;
    private final SipEncoder encoder = new SipEncoder();
    private final SipParser parser = new SipParser();

    private final AtomicLong cseqCounter = new AtomicLong(1);
    private final String localTag = UUID.randomUUID().toString().substring(0, 8);
    private final Map<String, String> credentials = new ConcurrentHashMap<>();

    private volatile String remoteTag;
    private volatile DialogState dialogState = DialogState.NONE;
    private volatile SipRequest lastReceivedInvite;
    private volatile SipRequest lastSentInvite;
    private volatile SipRequest lastReceivedRequest;
    private volatile SipRequest lastSentRequest;
    private volatile SipResponse lastReceivedProvisional;

    public VirtualUserAgent(String name,
                            String sipUri,
                            InetSocketAddress boundAddress,
                            SipTransport transport,
                            Channel channel,
                            EventLoopGroup eventLoopGroup,
                            ScenarioContext context) {
        this.name = name;
        this.sipUri = sipUri;
        this.boundAddress = boundAddress;
        this.transport = transport;
        this.channel = channel;
        this.eventLoopGroup = eventLoopGroup;
        this.context = context;
        this.mailbox = new SipMailbox(name);
    }

    public String getName() {
        return name;
    }

    public String getSipUri() {
        return sipUri;
    }

    public InetSocketAddress getBoundAddress() {
        return boundAddress;
    }

    public int getPort() {
        return boundAddress.getPort();
    }

    public SipTransport getTransport() {
        return transport;
    }

    public SipMailbox getMailbox() {
        return mailbox;
    }

    public DialogState getDialogState() {
        return dialogState;
    }

    public void setDialogState(DialogState dialogState) {
        this.dialogState = dialogState;
    }

    public void setCredentials(String username, String password) {
        if (username != null) {
            credentials.put("username", username);
        }
        if (password != null) {
            credentials.put("password", password);
        }
    }

    public String getUsername() {
        return credentials.get("username");
    }

    public String getPassword() {
        return credentials.get("password");
    }

    public String getContactHeader() {
        return String.format("<sip:%s@%s:%d>", name.toLowerCase(), boundAddress.getHostString(), boundAddress.getPort());
    }

    // ==========================================
    // Sending Operations
    // ==========================================

    public void sendRequest(SipRequest request, InetSocketAddress destination, String peerName) {
        prepareRequestDefaults(request);
        request.setRemoteAddress(destination);

        byte[] bytes = encoder.encode(request);
        channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(bytes), destination));

        context.getRecorder().record(new RecordedMessage(
                RecordedMessage.Direction.SENT,
                name,
                peerName,
                destination,
                request,
                Instant.now()
        ));
        context.setLastSentMessage(request);
        this.lastSentRequest = request;

        if (request.getMethod() == SipMethod.INVITE) {
            lastSentInvite = request;
            if (dialogState == DialogState.NONE) {
                dialogState = DialogState.EARLY;
            }
        }
    }

    public void sendResponse(SipResponse response, InetSocketAddress destination, String peerName) {
        response.setRemoteAddress(destination);
        byte[] bytes = encoder.encode(response);
        channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(bytes), destination));

        context.getRecorder().record(new RecordedMessage(
                RecordedMessage.Direction.SENT,
                name,
                peerName,
                destination,
                response,
                Instant.now()
        ));
        context.setLastSentMessage(response);

        if (response.isSuccess()) {
            String cseqMethod = extractCSeqMethod(response);
            if (cseqMethod != null && cseqMethod.equalsIgnoreCase("BYE")) {
                dialogState = DialogState.TERMINATED;
            } else if (dialogState != DialogState.TERMINATED) {
                dialogState = DialogState.CONFIRMED;
            }
        }
    }

    public void sendAck(VirtualUserAgent recipient) {
        SipRequest orig = lastSentInvite != null ? lastSentInvite : context.getLastRequest();
        SipResponse resp = context.getLastResponse();
        if (orig == null) {
            throw new IllegalStateException("Cannot send ACK: no prior INVITE found");
        }

        SipRequest.Builder builder = SipRequest.builder(SipMethod.ACK, recipient.getSipUri())
                .from(orig.getFrom())
                .to(resp != null && resp.getTo() != null ? resp.getTo() : orig.getTo())
                .callId(orig.getCallId())
                .cseq(orig.getCSeqNumber(), SipMethod.ACK)
                .contact(getContactHeader());

        SipRequest ack = builder.build();
        sendRequest(ack, recipient.getBoundAddress(), recipient.getName());
    }

    public void sendBye(VirtualUserAgent recipient) {
        SipRequest orig = lastSentInvite != null ? lastSentInvite : context.getLastRequest();
        if (orig == null) {
            throw new IllegalStateException("Cannot send BYE: no active dialog or INVITE found");
        }

        String toHeader = orig.getTo();
        if (remoteTag != null && !toHeader.contains("tag=")) {
            toHeader = toHeader + ";tag=" + remoteTag;
        }

        SipRequest bye = SipRequest.builder(SipMethod.BYE, recipient.getSipUri())
                .from("<" + sipUri + ">;tag=" + localTag)
                .to(toHeader)
                .callId(orig.getCallId())
                .cseq(cseqCounter.incrementAndGet(), SipMethod.BYE)
                .contact(getContactHeader())
                .build();

        sendRequest(bye, recipient.getBoundAddress(), recipient.getName());
        dialogState = DialogState.TERMINATED;
    }

    public void sendPrack(VirtualUserAgent recipient) {
        SipRequest orig = lastSentInvite != null ? lastSentInvite : context.getLastRequest();
        SipResponse prov = lastReceivedProvisional != null ? lastReceivedProvisional : context.getLastResponse();
        if (orig == null || prov == null) {
            throw new IllegalStateException("Cannot send PRACK: missing original INVITE or provisional response");
        }

        String rseq = prov.getHeaders().get("RSeq");
        if (rseq == null) {
            rseq = "1";
        }
        String cseqNumber = String.valueOf(orig.getCSeqNumber());

        SipRequest prack = SipRequest.builder(SipMethod.PRACK, recipient.getSipUri())
                .from(orig.getFrom())
                .to(prov.getTo() != null ? prov.getTo() : orig.getTo())
                .callId(orig.getCallId())
                .cseq(cseqCounter.incrementAndGet(), SipMethod.PRACK)
                .header("RAck", rseq + " " + cseqNumber + " " + orig.getMethod().name())
                .contact(getContactHeader())
                .build();

        sendRequest(prack, recipient.getBoundAddress(), recipient.getName());
    }

    public void retryWithAuth(SipResponse challenge, InetSocketAddress destination, String peerName) {
        SipRequest orig = this.lastSentRequest != null ? this.lastSentRequest : (SipRequest) context.getLastRequest();
        if (orig == null) {
            throw new IllegalStateException("Cannot retry with auth: no previous sent request");
        }

        String challengeHeader = challenge.getHeaders().get("WWW-Authenticate");
        if (challengeHeader == null) {
            challengeHeader = challenge.getHeaders().get("Proxy-Authenticate");
        }
        if (challengeHeader == null) {
            throw new IllegalStateException("Challenge response missing WWW-Authenticate or Proxy-Authenticate header");
        }

        String authHeader = SipAuthHelper.buildAuthorizationHeader(
                getUsername(),
                getPassword(),
                orig.getMethod().name(),
                orig.getUri().toString(),
                challengeHeader
        );

        SipRequest.Builder builder = SipRequest.builder(orig.getMethod(), orig.getUri())
                .from(orig.getFrom())
                .to(orig.getTo())
                .callId(orig.getCallId())
                .cseq(cseqCounter.incrementAndGet(), orig.getMethod())
                .contact(getContactHeader())
                .header(challenge.getStatusCode() == 407 ? "Proxy-Authorization" : "Authorization", authHeader);

        if (orig.getBody() != null && orig.getBody().length > 0) {
            builder.body(orig.getBodyAsString());
        }
        for (Map.Entry<String, java.util.List<String>> h : orig.getHeaders()) {
            String name = h.getKey();
            if (!name.equalsIgnoreCase("Via") && !name.equalsIgnoreCase("CSeq")
                    && !name.equalsIgnoreCase("Authorization") && !name.equalsIgnoreCase("Proxy-Authorization")
                    && !name.equalsIgnoreCase("From") && !name.equalsIgnoreCase("To")
                    && !name.equalsIgnoreCase("Call-ID") && !name.equalsIgnoreCase("Contact")) {
                for (String val : h.getValue()) {
                    builder.header(name, val);
                }
            }
        }

        SipRequest authReq = builder.build();
        sendRequest(authReq, destination, peerName);
    }

    // ==========================================
    // Receiving & Pipeline Handling
    // ==========================================

    public void onMessageReceived(SipMessage message, InetSocketAddress remote) {
        String peerName = resolvePeerName(remote);

        context.getRecorder().record(new RecordedMessage(
                RecordedMessage.Direction.RECEIVED,
                name,
                peerName,
                remote,
                message,
                Instant.now()
        ));
        context.setLastReceivedMessage(message);

        if (message instanceof SipRequest req) {
            this.lastReceivedRequest = req;
            if (req.getMethod() == SipMethod.INVITE) {
                lastReceivedInvite = req;
                dialogState = DialogState.EARLY;
            } else if (req.getMethod() == SipMethod.BYE) {
                dialogState = DialogState.TERMINATED;
            }
        } else if (message instanceof SipResponse resp) {
            if (resp.getTo() != null && resp.getTo().contains("tag=")) {
                int tagIdx = resp.getTo().indexOf("tag=");
                this.remoteTag = resp.getTo().substring(tagIdx + 4).split(";")[0].trim();
            }
            String cseqMethod = extractCSeqMethod(resp);
            if (resp.isProvisional()) {
                lastReceivedProvisional = resp;
                dialogState = DialogState.EARLY;
            } else if (resp.isSuccess() && cseqMethod != null && cseqMethod.equalsIgnoreCase("INVITE")) {
                dialogState = DialogState.CONFIRMED;
            } else if (resp.isSuccess() && cseqMethod != null && cseqMethod.equalsIgnoreCase("BYE")) {
                dialogState = DialogState.TERMINATED;
            }
        }

        mailbox.add(message);
    }

    private String extractCSeqMethod(SipResponse resp) {
        String cseq = resp.getCSeq();
        if (cseq != null) {
            String[] parts = cseq.trim().split("\\s+");
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        return null;
    }

    private String resolvePeerName(InetSocketAddress remote) {
        if (remote == null) {
            return "SUT";
        }
        for (VirtualUserAgent a : context.getAllActors().values()) {
            if (a != this && a.getBoundAddress().getPort() == remote.getPort()) {
                return a.getName();
            }
        }
        return "SUT";
    }

    private void prepareRequestDefaults(SipRequest req) {
        if (req.getFrom() == null || !req.getFrom().contains("tag=")) {
            String from = req.getFrom() != null ? req.getFrom() : "<" + sipUri + ">";
            if (!from.contains("tag=")) {
                from = from + ";tag=" + localTag;
            }
            req.getHeaders().set("From", from);
        }

        if (req.getCallId() == null) {
            req.getHeaders().set("Call-ID", context.getOrCreateCallId());
        }

        if (req.getVia() == null) {
            String branch = "z9hG4bK-" + UUID.randomUUID().toString().substring(0, 12);
            req.getHeaders().set("Via", String.format("SIP/2.0/%s %s:%d;branch=%s;rport",
                    transport.name(), boundAddress.getHostString(), boundAddress.getPort(), branch));
        }

        if (req.getHeaders().get("CSeq") == null) {
            req.getHeaders().set("CSeq", cseqCounter.getAndIncrement() + " " + req.getMethod().name());
        }

        if (req.getHeaders().get("Contact") == null) {
            req.getHeaders().set("Contact", getContactHeader());
        }

        if (req.getHeaders().get("Max-Forwards") == null) {
            req.getHeaders().set("Max-Forwards", "70");
        }
    }

    public SipRequest getLastReceivedInvite() {
        return lastReceivedInvite;
    }

    public SipRequest getLastReceivedRequest() {
        return lastReceivedRequest;
    }

    public SipRequest getLastSentRequest() {
        return lastSentRequest;
    }

    @Override
    public void close() {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        if (eventLoopGroup != null && !eventLoopGroup.isShuttingDown()) {
            eventLoopGroup.shutdownGracefully();
        }
        mailbox.clear();
    }
}
