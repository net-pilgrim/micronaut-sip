package net.pilgrim.sip;

import net.pilgrim.sip.annotation.OnCancel;
import net.pilgrim.sip.annotation.OnInvite;
import net.pilgrim.sip.annotation.SipCallId;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.router.SipDispatcher;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class SipCancelTest {

    private SipSessionManager sessionManager;
    private SipServerConfiguration configuration;
    private SipDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        sessionManager = new SipSessionManager(Duration.ofMinutes(10), 100);
        configuration = new SipServerConfiguration();
        configuration.setAuto100TryingEnabled(false);
        configuration.setAutoCancelEnabled(true);
        dispatcher = new SipDispatcher(null, sessionManager, configuration);
    }

    @AfterEach
    void tearDown() {
    }

    @SipController
    static class AsyncCallController {
        final AtomicBoolean cancelCalled = new AtomicBoolean(false);

        @OnInvite
        public Flux<SipResponse> onInvite(SipRequest req, SipSession session) {
            session.setState(SipSession.State.EARLY);
            SipResponse ringing = SipResponse.ringing(req);
            SipResponse ok = SipResponse.ok(req);
            return Flux.concat(
                    Mono.just(ringing),
                    Mono.just(ok).delayElement(Duration.ofMillis(300))
            );
        }

        @OnCancel
        public void onCancel(SipRequest req, @SipCallId String callId, SipSession session) {
            cancelCalled.set(true);
            if (session != null) {
                session.setState(SipSession.State.TERMINATED);
            }
        }
    }

    @Test
    void testCancelMatchingPendingInviteEmits200And487() throws Exception {
        AsyncCallController controller = new AsyncCallController();
        dispatcher.registerController(controller);

        String callId = "cancel-test-call-1";
        String branch = "z9hG4bK-cancel-branch-1";
        InetSocketAddress remote = new InetSocketAddress("127.0.0.1", 5060);

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com;tag=alice1")
                .to("sip:bob@biloxi.com")
                .callId(callId)
                .cseq(1, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=" + branch)
                .remoteAddress(remote)
                .build();
        invite.setTransport(SipTransport.UDP);

        List<SipResponse> inviteResponses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch inviteRingingLatch = new CountDownLatch(1);

        dispatcher.dispatch(invite, resp -> {
            inviteResponses.add(resp);
            if (resp.getStatusCode() == 180) {
                inviteRingingLatch.countDown();
            }
        });

        // 1. Wait for 180 Ringing to be emitted
        assertTrue(inviteRingingLatch.await(1, TimeUnit.SECONDS), "INVITE should emit 180 Ringing");
        assertEquals(1, inviteResponses.size());
        assertEquals(180, inviteResponses.get(0).getStatusCode());
        assertEquals(1, dispatcher.getPendingTransactionCount(), "Pending transaction should be tracked");

        // 2. Dispatch CANCEL while 200 OK is still pending in delayElement
        SipRequest cancel = new SipRequest(SipMethod.CANCEL, "sip:bob@biloxi.com");
        cancel.setTransport(SipTransport.UDP);
        cancel.setRemoteAddress(remote);
        cancel.getHeaders().setFrom("sip:alice@atlanta.com;tag=alice1");
        cancel.getHeaders().setTo("sip:bob@biloxi.com");
        cancel.getHeaders().setCallId(callId);
        cancel.getHeaders().setCSeq("1 CANCEL");
        cancel.getHeaders().addVia("SIP/2.0/UDP 127.0.0.1:5060;branch=" + branch);

        List<SipResponse> cancelResponses = Collections.synchronizedList(new ArrayList<>());
        dispatcher.dispatch(cancel, cancelResponses::add);

        // 3. Verify CANCEL received 200 OK immediately
        assertEquals(1, cancelResponses.size());
        assertEquals(200, cancelResponses.get(0).getStatusCode());

        // 4. Verify pending INVITE received 487 Request Terminated
        assertEquals(2, inviteResponses.size());
        assertEquals(487, inviteResponses.get(1).getStatusCode());

        // 5. Verify @OnCancel was invoked
        assertTrue(controller.cancelCalled.get(), "@OnCancel method should have been invoked");

        // 6. Verify session was updated to TERMINATED
        SipSession session = sessionManager.getSession(callId);
        assertNotNull(session);
        assertEquals(SipSession.State.TERMINATED, session.getState());

        // 7. Verify pending transaction is cleared
        assertEquals(0, dispatcher.getPendingTransactionCount());

        // 8. Sleep beyond the 300ms delay to verify the 200 OK was cancelled and never emitted
        Thread.sleep(400);
        assertEquals(2, inviteResponses.size(), "200 OK must NOT be emitted after cancellation");
    }

    @Test
    void testCancelNonExistentTransactionReturns481() {
        SipRequest cancel = new SipRequest(SipMethod.CANCEL, "sip:bob@biloxi.com");
        cancel.setTransport(SipTransport.UDP);
        cancel.getHeaders().setFrom("sip:alice@atlanta.com");
        cancel.getHeaders().setTo("sip:bob@biloxi.com");
        cancel.getHeaders().setCallId("non-existent-call");
        cancel.getHeaders().setCSeq("1 CANCEL");
        cancel.getHeaders().addVia("SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-unknown");

        List<SipResponse> cancelResponses = new ArrayList<>();
        dispatcher.dispatch(cancel, cancelResponses::add);

        assertEquals(1, cancelResponses.size());
        assertEquals(481, cancelResponses.get(0).getStatusCode());
        assertEquals("Call/Transaction Does Not Exist", cancelResponses.get(0).getReasonPhrase());
    }

    @SipController
    static class SyncCallController {
        @OnInvite
        public SipResponse onInvite(SipRequest req) {
            return SipResponse.ok(req);
        }
    }

    @Test
    void testCancelAfterFinalResponseReturns481() {
        dispatcher.registerController(new SyncCallController());

        String callId = "sync-completed-call";
        String branch = "z9hG4bK-sync-1";

        SipRequest invite = SipRequest.builder(SipMethod.INVITE, "sip:bob@biloxi.com")
                .from("sip:alice@atlanta.com")
                .to("sip:bob@biloxi.com")
                .callId(callId)
                .cseq(1, SipMethod.INVITE)
                .via("SIP/2.0/UDP 127.0.0.1:5060;branch=" + branch)
                .build();
        invite.setTransport(SipTransport.UDP);

        List<SipResponse> inviteResponses = new ArrayList<>();
        dispatcher.dispatch(invite, inviteResponses::add);

        assertEquals(1, inviteResponses.size());
        assertEquals(200, inviteResponses.get(0).getStatusCode());
        assertEquals(0, dispatcher.getPendingTransactionCount());

        // Now dispatch CANCEL for already finalized transaction
        SipRequest cancel = new SipRequest(SipMethod.CANCEL, "sip:bob@biloxi.com");
        cancel.setTransport(SipTransport.UDP);
        cancel.getHeaders().setFrom("sip:alice@atlanta.com");
        cancel.getHeaders().setTo("sip:bob@biloxi.com");
        cancel.getHeaders().setCallId(callId);
        cancel.getHeaders().setCSeq("1 CANCEL");
        cancel.getHeaders().addVia("SIP/2.0/UDP 127.0.0.1:5060;branch=" + branch);

        List<SipResponse> cancelResponses = new ArrayList<>();
        dispatcher.dispatch(cancel, cancelResponses::add);

        assertEquals(1, cancelResponses.size());
        assertEquals(481, cancelResponses.get(0).getStatusCode());
    }
}
