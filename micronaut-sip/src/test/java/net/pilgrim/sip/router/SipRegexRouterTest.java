package net.pilgrim.sip.router;

import net.pilgrim.sip.annotation.OnInvite;
import net.pilgrim.sip.annotation.SipController;
import net.pilgrim.sip.annotation.SipVar;
import net.pilgrim.sip.model.SipMethod;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.session.SipSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SipRegexRouterTest {

    private SipSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new SipSessionManager();
    }

    @SipController
    static class RouterTestController {
        String lastInvoked;
        String capturedId;
        int capturedExt;

        @OnInvite("users/admin")
        public void handleAdmin() {
            this.lastInvoked = "admin";
        }

        @OnInvite("users/{id:[0-9]+}")
        public void handleUserNumeric(@SipVar("id") String id) {
            this.lastInvoked = "user-numeric";
            this.capturedId = id;
        }

        @OnInvite("regex:^sip:(?<ext>[0-9]{3,4})@.*")
        public void handleExtension(@SipVar("ext") int ext) {
            this.lastInvoked = "extension";
            this.capturedExt = ext;
        }

        @OnInvite("room/{roomId}")
        public void handleRoom(@SipVar String roomId) {
            this.lastInvoked = "room";
            this.capturedId = roomId;
        }

        @OnInvite
        public void handleCatchAll() {
            this.lastInvoked = "catch-all";
        }
    }

    @Test
    void testPatternMatcherTemplateExtraction() {
        SipPatternMatcher matcher = new SipPatternMatcher("users/{id:[0-9]+}");
        assertTrue(matcher.isRegex());
        assertEquals(1, matcher.getVariableNames().size());
        assertEquals("id", matcher.getVariableNames().get(0));

        SipRequest matchReq = SipRequest.builder(SipMethod.INVITE, "sip:users/42@example.com").build();
        Optional<java.util.Map<String, String>> vars = matcher.match(matchReq);
        assertTrue(vars.isPresent());
        assertEquals("42", vars.get().get("id"));

        SipRequest nonMatchReq = SipRequest.builder(SipMethod.INVITE, "sip:users/john@example.com").build();
        assertFalse(matcher.match(nonMatchReq).isPresent());
    }

    @Test
    void testPatternMatcherExplicitRegex() {
        SipPatternMatcher matcher = new SipPatternMatcher("regex:^sip:(?<ext>[0-9]{3,4})@.*");
        assertTrue(matcher.isRegex());
        assertEquals(1, matcher.getVariableNames().size());
        assertEquals("ext", matcher.getVariableNames().get(0));

        SipRequest matchReq = SipRequest.builder(SipMethod.INVITE, "sip:2001@example.com").build();
        Optional<java.util.Map<String, String>> vars = matcher.match(matchReq);
        assertTrue(vars.isPresent());
        assertEquals("2001", vars.get().get("ext"));

        SipRequest nonMatchReq = SipRequest.builder(SipMethod.INVITE, "sip:12@example.com").build();
        assertFalse(matcher.match(nonMatchReq).isPresent());
    }

    @Test
    void testRoutePrecedenceSorting() throws Exception {
        RouterTestController controller = new RouterTestController();

        Method mAdmin = RouterTestController.class.getMethod("handleAdmin");
        Method mUserNum = RouterTestController.class.getMethod("handleUserNumeric", String.class);
        Method mExt = RouterTestController.class.getMethod("handleExtension", int.class);
        Method mRoom = RouterTestController.class.getMethod("handleRoom", String.class);
        Method mCatchAll = RouterTestController.class.getMethod("handleCatchAll");

        SipRoute rAdmin = new SipRoute(SipMethod.INVITE, null, "users/admin", controller, mAdmin);
        SipRoute rUserNum = new SipRoute(SipMethod.INVITE, null, "users/{id:[0-9]+}", controller, mUserNum);
        SipRoute rExt = new SipRoute(SipMethod.INVITE, null, "regex:^sip:(?<ext>[0-9]{3,4})@.*", controller, mExt);
        SipRoute rRoom = new SipRoute(SipMethod.INVITE, null, "room/{roomId}", controller, mRoom);
        SipRoute rCatchAll = new SipRoute(SipMethod.INVITE, null, "", controller, mCatchAll);

        java.util.List<SipRoute> routes = new java.util.ArrayList<>(java.util.List.of(
                rCatchAll, rRoom, rExt, rUserNum, rAdmin
        ));
        routes.sort(null);

        // Precedence: Exact (Admin) > Templates/Regex > CatchAll
        assertEquals(rAdmin, routes.get(0), "Exact match must have highest precedence");
        assertEquals(rCatchAll, routes.get(routes.size() - 1), "Catch-all must have lowest precedence");
    }

    @Test
    void testInvocationAndSipVarBinding() throws Exception {
        RouterTestController controller = new RouterTestController();
        Method mUserNum = RouterTestController.class.getMethod("handleUserNumeric", String.class);
        SipRoute route = new SipRoute(SipMethod.INVITE, null, "users/{id:[0-9]+}", controller, mUserNum);

        SipRequest request = SipRequest.builder(SipMethod.INVITE, "sip:users/999@domain.com").build();
        assertTrue(route.matches(request));

        route.invoke(request, sessionManager);
        assertEquals("user-numeric", controller.lastInvoked);
        assertEquals("999", controller.capturedId);
    }

    @Test
    void testExtensionRegexBinding() throws Exception {
        RouterTestController controller = new RouterTestController();
        Method mExt = RouterTestController.class.getMethod("handleExtension", int.class);
        SipRoute route = new SipRoute(SipMethod.INVITE, null, "regex:^sip:(?<ext>[0-9]{3,4})@.*", controller, mExt);

        SipRequest request = SipRequest.builder(SipMethod.INVITE, "sip:5001@telecom.org").build();
        assertTrue(route.matches(request));

        route.invoke(request, sessionManager);
        assertEquals("extension", controller.lastInvoked);
        assertEquals(5001, controller.capturedExt);
    }
}
