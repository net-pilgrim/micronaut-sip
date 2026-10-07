package net.pilgrim.sip.session.state;

import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.model.SipUri;
import net.pilgrim.sip.session.SipSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Harel Statechart SIP Dialog Architecture Tests")
class SipDialogStatechartTest {

    private SipSession session;
    private SipRequest inviteRequest;

    @BeforeEach
    void setUp() {
        session = new SipSession("test-call-100", new InetSocketAddress("127.0.0.1", 5060));
        inviteRequest = new SipRequest(SipMethod.INVITE, SipUri.parse("sip:alice@example.com"));
        inviteRequest.getHeaders().setCallId("test-call-100");
        inviteRequest.getHeaders().setCSeq("1 INVITE");
    }

    @Test
    @DisplayName("Initial state processes INVITE and transitions to EARLY on provisional response")
    void testInitialToEarlyTransition() {
        assertEquals(SipSession.State.INITIAL, session.getState());
        assertTrue(session.getDialogState() instanceof InitialDialogState);

        String offer = "v=0\r\no=alice 100 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 49170 RTP/AVP 0\r\n";
        DialogTransitionResult inviteRes = session.handleInvite(inviteRequest, offer);
        assertTrue(inviteRes.successful());
        assertEquals(SipSession.State.INITIAL, session.getState());
        assertEquals(offer, session.getOfferAnswerContext().getOffer());

        SipResponse ringing = SipResponse.ringing(inviteRequest);
        DialogTransitionResult provRes = session.handleProvisional(ringing);
        assertTrue(provRes.successful());
        assertEquals(SipSession.State.EARLY, session.getState());
        assertTrue(session.isEarly());
        assertTrue(session.getDialogState() instanceof EarlyDialogState);
    }

    @Test
    @DisplayName("Early state transitions to CONFIRMED on valid ACK")
    void testEarlyToConfirmedTransition() {
        session.handleProvisional(SipResponse.ringing(inviteRequest));
        assertEquals(SipSession.State.EARLY, session.getState());

        SipRequest ack = new SipRequest(SipMethod.ACK, SipUri.parse("sip:alice@example.com"));
        DialogTransitionResult ackRes = session.handleAck(ack, null);

        assertTrue(ackRes.successful());
        assertTrue(ackRes.isConfirmed());
        assertEquals(SipSession.State.CONFIRMED, session.getState());
        assertTrue(session.isConfirmed());
        assertTrue(session.getDialogState() instanceof ConfirmedDialogState);
    }

    @Test
    @DisplayName("Late-offer negotiation consumes SDP answer during handleAck")
    void testLateOfferNegotiationOnAck() {
        session.handleInvite(inviteRequest, null);
        session.getOfferAnswerContext().setLocalOffer("v=0\r\nm=audio 4000 RTP/AVP 0\r\n");
        session.handleProvisional(SipResponse.ringing(inviteRequest));

        assertTrue(session.getOfferAnswerContext().isAwaitingAckAnswer());

        String ackAnswer = "v=0\r\nm=audio 5000 RTP/AVP 0\r\n";
        SipRequest ack = new SipRequest(SipMethod.ACK, SipUri.parse("sip:alice@example.com"));
        DialogTransitionResult ackRes = session.handleAck(ack, ackAnswer);

        assertTrue(ackRes.successful());
        assertEquals(SipSession.State.CONFIRMED, session.getState());
        assertEquals(ackAnswer, session.getOfferAnswerContext().getAnswer());
        assertTrue(session.getOfferAnswerContext().isStable());
        assertFalse(session.getOfferAnswerContext().isAwaitingAckAnswer());
    }

    @Test
    @DisplayName("RFC 3262 PRACK validates RAck and completes sink in EARLY state")
    void testReliableProvisionalPrackValidation() {
        session.handleProvisional(SipResponse.ringing(inviteRequest));

        Sinks.One<Void> prackSink = Sinks.one();
        session.getReliableContext().initiate(1L, 1L, "INVITE", prackSink);
        assertEquals(ReliableProvisionalState.AWAITING_PRACK, session.getReliableContext().getState());

        // Incorrect RAck sequence number
        SipRequest badPrack = new SipRequest(SipMethod.PRACK, SipUri.parse("sip:alice@example.com"));
        DialogTransitionResult badRes = session.handlePrack(badPrack, "2 1 INVITE", null);
        assertFalse(badRes.successful());
        assertTrue(badRes.isRejected());

        // Valid RAck
        SipRequest goodPrack = new SipRequest(SipMethod.PRACK, SipUri.parse("sip:alice@example.com"));
        DialogTransitionResult goodRes = session.handlePrack(goodPrack, "1 1 INVITE", null);
        assertTrue(goodRes.successful());
        assertFalse(goodRes.isRejected());
        assertEquals(ReliableProvisionalState.PRACK_RESOLVED, session.getReliableContext().getState());
    }

    @Test
    @DisplayName("Active dialog terminates on BYE")
    void testByeTermination() {
        session.handleProvisional(SipResponse.ringing(inviteRequest));
        session.handleAck(new SipRequest(SipMethod.ACK, SipUri.parse("sip:alice@example.com")), null);
        assertEquals(SipSession.State.CONFIRMED, session.getState());

        SipRequest bye = new SipRequest(SipMethod.BYE, SipUri.parse("sip:alice@example.com"));
        DialogTransitionResult byeRes = session.handleBye(bye);

        assertTrue(byeRes.successful());
        assertTrue(byeRes.isTerminated());
        assertEquals(SipSession.State.TERMINATED, session.getState());
        assertTrue(session.isTerminated());
        assertTrue(session.getDialogState() instanceof TerminatedDialogState);
    }

    @Test
    @DisplayName("Early dialog terminates on CANCEL")
    void testCancelTermination() {
        session.handleProvisional(SipResponse.ringing(inviteRequest));
        assertEquals(SipSession.State.EARLY, session.getState());

        SipRequest cancel = new SipRequest(SipMethod.CANCEL, SipUri.parse("sip:alice@example.com"));
        DialogTransitionResult cancelRes = session.handleCancel(cancel);

        assertTrue(cancelRes.successful());
        assertTrue(cancelRes.isTerminated());
        assertEquals(SipSession.State.TERMINATED, session.getState());
        assertTrue(session.isTerminated());
    }

    @Test
    @DisplayName("RFC 3261 §12.3: Terminated dialog cannot be resurrected")
    void testTerminatedSinkInvariant() {
        session.setState(SipSession.State.TERMINATED);
        assertTrue(session.isTerminated());
        assertTrue(session.getDialogState().isTerminal());

        // Attempting to reset state via setState
        session.setState(SipSession.State.CONFIRMED);
        assertEquals(SipSession.State.TERMINATED, session.getState());

        session.setState(SipSession.State.EARLY);
        assertEquals(SipSession.State.TERMINATED, session.getState());

        // Attempting to transition via transitionTo
        session.transitionTo(new ConfirmedDialogState());
        assertEquals(SipSession.State.TERMINATED, session.getState());

        // Attempting events on terminated dialog
        DialogTransitionResult invRes = session.handleInvite(inviteRequest, null);
        assertFalse(invRes.successful());
        assertTrue(invRes.isRejected());

        DialogTransitionResult ackRes = session.handleAck(new SipRequest(SipMethod.ACK, SipUri.parse("sip:alice@example.com")), null);
        assertFalse(ackRes.isConfirmed());
        assertEquals(SipSession.State.TERMINATED, ackRes.newState());
        assertEquals(SipSession.State.TERMINATED, session.getState());
    }

    @Test
    @DisplayName("Backward compatibility attribute bridges sync correctly")
    void testAttributeBridgeSync() {
        session.setAttribute("sdpOffer", "offer-sdp");
        assertEquals("offer-sdp", session.getAttribute("sdpOffer"));
        assertEquals("offer-sdp", session.getOfferAnswerContext().getOffer());

        session.setAttribute("sdpAnswer", "answer-sdp");
        assertEquals("answer-sdp", session.getAttribute("sdpAnswer"));
        assertEquals("answer-sdp", session.getOfferAnswerContext().getAnswer());

        session.setAttribute("rseq", 42L);
        assertEquals(42L, (Long) session.getAttribute("rseq"));
        assertEquals(42L, session.getReliableContext().getRSeq());
    }
}
