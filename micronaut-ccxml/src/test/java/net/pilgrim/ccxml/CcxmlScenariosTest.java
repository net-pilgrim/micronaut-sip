package net.pilgrim.ccxml;

import net.pilgrim.ccxml.ast.CcxmlDocument;
import net.pilgrim.ccxml.bridge.*;
import net.pilgrim.ccxml.parser.CcxmlParser;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification of four primary CCXML 1.0 telephony call control scenarios:
 * 1. Attended Transfer with IVR Fallback
 * 2. Multi-Party Conferencing with PIN Verification
 * 3. Whisper Coaching / Supervisor Monitoring (duplex="half")
 * 4. Outbound Campaign Dialing
 */
class CcxmlScenariosTest {

    private final CcxmlParser parser = new CcxmlParser();
    private InMemoryConnectionBridge connectionBridge;
    private InMemoryDialogBridge dialogBridge;
    private InMemoryMediaBridge mediaBridge;

    @BeforeEach
    void setUp() {
        connectionBridge = new InMemoryConnectionBridge();
        dialogBridge = new InMemoryDialogBridge();
        mediaBridge = new InMemoryMediaBridge();
    }

    private CcxmlSession createSession(String xml) {
        CcxmlDocument doc = parser.parse(xml);
        return new CcxmlSession("test-session", doc, connectionBridge, dialogBridge, mediaBridge);
    }

    @Test
    @DisplayName("Scenario A1: Attended Transfer - Specialist Answers and Legs are Joined")
    void testScenarioA1_AttendedTransferSuccess() {
        String ccxml = """
            <ccxml version="1.0">
                <var name="inboundLeg"/>
                <var name="outboundLeg"/>
                <var name="activeDialog"/>

                <eventprocessor statevariable="callState">
                    <!-- Step 1: Inbound call alerting -->
                    <transition event="connection.alerting" state="init">
                        <assign name="inboundLeg" expr="event$.connectionid"/>
                        <accept connectionid="inboundLeg"/>
                        <assign name="callState" expr="'inbound_accepted'"/>
                    </transition>

                    <!-- Step 2: Inbound call connected, start greeting IVR dialog -->
                    <transition event="connection.connected" state="inbound_accepted">
                        <dialogstart src="'http://media/menu.vxml'" dialogid="activeDialog" connectionid="inboundLeg"/>
                        <assign name="callState" expr="'ivr_active'"/>
                    </transition>

                    <!-- Step 3: IVR dialog collects user choice to transfer to specialist -->
                    <transition event="dialog.exit" state="ivr_active">
                        <if cond="event$.values.target == 'specialist'">
                            <createcall dest="'sip:specialist@pbx.internal'" callerid="'18005550199'" connectionid="outboundLeg"/>
                            <assign name="callState" expr="'calling_specialist'"/>
                        <else/>
                            <disconnect connectionid="inboundLeg"/>
                            <exit/>
                        </if>
                    </transition>

                    <!-- Step 4: Specialist answers (200 OK), join both legs full duplex -->
                    <transition event="connection.connected" state="calling_specialist">
                        <join id1="inboundLeg" id2="outboundLeg" duplex="full"/>
                        <assign name="callState" expr="'in_conversation'"/>
                    </transition>

                    <!-- Step 5: Disconnect handling -->
                    <transition event="connection.disconnected">
                        <disconnect connectionid="inboundLeg"/>
                        <disconnect connectionid="outboundLeg"/>
                        <exit/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlSession session = createSession(ccxml);
        assertEquals("init", session.getCurrentState());

        // 1. Inbound call arrives
        session.processEvent(new CcxmlEvent("connection.alerting", "conn_caller_101"));
        assertEquals("inbound_accepted", session.getCurrentState());
        assertEquals("conn_caller_101", session.getVariable("inboundLeg"));
        assertTrue(connectionBridge.getCallLog().contains("ACCEPT:conn_caller_101"));

        // 2. Inbound connected -> start IVR dialog
        session.processEvent(new CcxmlEvent("connection.connected", "conn_caller_101"));
        assertEquals("ivr_active", session.getCurrentState());
        assertNotNull(session.getVariable("activeDialog"));
        assertTrue(dialogBridge.getDialogLog().stream().anyMatch(log -> log.contains("menu.vxml")));

        // 3. User selects 1 (Specialist) -> dialog exits with target='specialist'
        CcxmlEvent dlgExit = new CcxmlEvent("dialog.exit", "conn_caller_101", "dlg_1", null,
                Map.of("target", "specialist"));
        session.processEvent(dlgExit);
        assertEquals("calling_specialist", session.getCurrentState());
        assertNotNull(session.getVariable("outboundLeg"));
        String specialistConnId = (String) session.getVariable("outboundLeg");
        assertTrue(connectionBridge.getCallLog().stream().anyMatch(log -> log.contains("sip:specialist@pbx.internal")));

        // 4. Specialist answers -> join caller and specialist
        session.processEvent(new CcxmlEvent("connection.connected", specialistConnId));
        assertEquals("in_conversation", session.getCurrentState());
        assertTrue(mediaBridge.isJoined("conn_caller_101", specialistConnId));
        assertEquals(DuplexMode.FULL, mediaBridge.getDuplex("conn_caller_101", specialistConnId));
        assertTrue(mediaBridge.getMediaLog().contains("JOIN:conn_caller_101<->" + specialistConnId + ":FULL"));
    }

    @Test
    @DisplayName("Scenario A2: Attended Transfer - Specialist Busy, Fallback to Voicemail IVR")
    void testScenarioA2_AttendedTransferFallback() {
        String ccxml = """
            <ccxml version="1.0">
                <var name="inboundLeg"/>
                <var name="outboundLeg"/>
                <var name="activeDialog"/>

                <eventprocessor statevariable="callState">
                    <transition event="connection.alerting" state="init">
                        <assign name="inboundLeg" expr="event$.connectionid"/>
                        <accept connectionid="inboundLeg"/>
                        <assign name="callState" expr="'inbound_accepted'"/>
                    </transition>

                    <transition event="connection.connected" state="inbound_accepted">
                        <dialogstart src="'http://media/menu.vxml'" dialogid="activeDialog" connectionid="inboundLeg"/>
                        <assign name="callState" expr="'ivr_active'"/>
                    </transition>

                    <transition event="dialog.exit" state="ivr_active">
                        <createcall dest="'sip:specialist@pbx.internal'" connectionid="outboundLeg"/>
                        <assign name="callState" expr="'calling_specialist'"/>
                    </transition>

                    <!-- Specialist failed (busy/no answer) -> Fallback dialog -->
                    <transition event="connection.failed" state="calling_specialist">
                        <dialogstart src="'http://media/busy_fallback.vxml'" dialogid="activeDialog" connectionid="inboundLeg"/>
                        <assign name="callState" expr="'fallback_ivr'"/>
                    </transition>

                    <!-- Fallback IVR finishes -> Disconnect caller -->
                    <transition event="dialog.exit" state="fallback_ivr">
                        <disconnect connectionid="inboundLeg"/>
                        <exit/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlSession session = createSession(ccxml);

        // Alerting & Accept
        session.processEvent(new CcxmlEvent("connection.alerting", "caller_leg"));
        session.processEvent(new CcxmlEvent("connection.connected", "caller_leg"));
        assertEquals("ivr_active", session.getCurrentState());

        // Dialog exit -> Calling specialist
        session.processEvent(new CcxmlEvent("dialog.exit", "caller_leg", "dlg_ivr", null, Map.of()));
        assertEquals("calling_specialist", session.getCurrentState());
        String outConnId = (String) session.getVariable("outboundLeg");

        // Specialist connection failed (486 Busy / 408 Timeout)
        session.processEvent(new CcxmlEvent("connection.failed", outConnId));
        assertEquals("fallback_ivr", session.getCurrentState());
        assertTrue(dialogBridge.getDialogLog().stream().anyMatch(log -> log.contains("busy_fallback.vxml")));

        // Fallback IVR completes -> Session terminates & caller disconnected
        session.processEvent(new CcxmlEvent("dialog.exit", "caller_leg", "dlg_fallback", null, Map.of()));
        assertTrue(session.isTerminated());
        assertTrue(connectionBridge.getCallLog().contains("DISCONNECT:caller_leg:null"));
    }

    @Test
    @DisplayName("Scenario B: Multi-Party Conferencing with VoiceXML PIN Verification")
    void testScenarioB_MultiPartyConferenceWithPinVerification() {
        String ccxml = """
            <ccxml version="1.0">
                <var name="confId"/>
                <var name="activeDialog"/>

                <eventprocessor statevariable="sysState">
                    <!-- Initialize Conference Room on startup -->
                    <transition event="system.init">
                        <createconference conferenceid="confId" confname="'financial_sync'" reservedtalkers="50"/>
                        <assign name="sysState" expr="'conf_ready'"/>
                    </transition>

                    <!-- Inbound caller arrives -->
                    <transition event="connection.alerting" state="conf_ready">
                        <accept connectionid="event$.connectionid"/>
                    </transition>

                    <transition event="connection.connected" state="conf_ready">
                        <dialogstart src="'http://media/auth_pin.vxml'" dialogid="activeDialog" connectionid="event$.connectionid"/>
                    </transition>

                    <!-- PIN Dialog outcome -->
                    <transition event="dialog.exit" state="conf_ready">
                        <if cond="event$.values.pin_valid == true">
                            <join id1="event$.connectionid" id2="confId" duplex="full"/>
                        <else/>
                            <disconnect connectionid="event$.connectionid"/>
                        </if>
                    </transition>

                    <!-- Caller hangs up -> unjoin from conference -->
                    <transition event="connection.disconnected" state="conf_ready">
                        <unjoin id1="event$.connectionid" id2="confId"/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlSession session = createSession(ccxml);

        // 1. Initialize conference room
        session.processEvent(new CcxmlEvent("system.init"));
        assertEquals("conf_ready", session.getCurrentState());
        assertNotNull(session.getVariable("confId"));
        String confId = (String) session.getVariable("confId");
        assertTrue(mediaBridge.getMediaLog().contains("CREATE_CONF:" + confId + ":financial_sync"));

        // 2. Caller 1 dials in & answers
        String caller1 = "leg_caller_1";
        session.processEvent(new CcxmlEvent("connection.alerting", caller1));
        session.processEvent(new CcxmlEvent("connection.connected", caller1));

        // Caller 1 enters valid PIN
        CcxmlEvent authCaller1 = new CcxmlEvent("dialog.exit", caller1, "dlg_auth1", null,
                Map.of("pin_valid", true));
        session.processEvent(authCaller1);
        assertTrue(mediaBridge.isJoined(caller1, confId));
        assertEquals(DuplexMode.FULL, mediaBridge.getDuplex(caller1, confId));

        // 3. Caller 2 dials in & answers
        String caller2 = "leg_caller_2";
        session.processEvent(new CcxmlEvent("connection.alerting", caller2));
        session.processEvent(new CcxmlEvent("connection.connected", caller2));

        // Caller 2 enters valid PIN
        CcxmlEvent authCaller2 = new CcxmlEvent("dialog.exit", caller2, "dlg_auth2", null,
                Map.of("pin_valid", true));
        session.processEvent(authCaller2);
        assertTrue(mediaBridge.isJoined(caller2, confId));

        // 4. Verify both callers are in conference
        assertTrue(mediaBridge.isJoined(caller1, confId));
        assertTrue(mediaBridge.isJoined(caller2, confId));

        // 5. Caller 1 hangs up -> unjoin caller 1, caller 2 remains
        session.processEvent(new CcxmlEvent("connection.disconnected", caller1));
        assertFalse(mediaBridge.isJoined(caller1, confId));
        assertTrue(mediaBridge.isJoined(caller2, confId));
        assertTrue(mediaBridge.getMediaLog().contains("UNJOIN:" + caller1 + "<->" + confId));
    }

    @Test
    @DisplayName("Scenario C: Whisper Coaching / Supervisor Monitoring (duplex='half')")
    void testScenarioC_WhisperCoachingHalfDuplex() {
        String ccxml = """
            <ccxml version="1.0">
                <var name="customerLeg" expr="'leg_cust_1'"/>
                <var name="agentLeg" expr="'leg_agent_1'"/>

                <eventprocessor statevariable="state">
                    <!-- Base conversation between customer and agent -->
                    <transition event="setup.call" state="init">
                        <join id1="customerLeg" id2="agentLeg" duplex="full"/>
                        <assign name="state" expr="'call_active'"/>
                    </transition>

                    <!-- Supervisor joins the active call for whisper coaching -->
                    <transition event="supervisor.join" state="call_active">
                        <accept connectionid="event$.connectionid"/>
                        <!-- Whisper: supervisor audio goes to agent only in half duplex -->
                        <join id1="event$.connectionid" id2="agentLeg" duplex="half"/>
                        <!-- Supervisor hears both customer and agent -->
                        <join id1="customerLeg" id2="event$.connectionid" duplex="half"/>
                        <assign name="state" expr="'whisper_coaching_active'"/>
                    </transition>

                    <transition event="supervisor.leave" state="whisper_coaching_active">
                        <unjoin id1="event$.connectionid" id2="agentLeg"/>
                        <unjoin id1="customerLeg" id2="event$.connectionid"/>
                        <assign name="state" expr="'call_active'"/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlSession session = createSession(ccxml);

        // 1. Initial full duplex call between customer and agent
        session.processEvent(new CcxmlEvent("setup.call"));
        assertEquals("call_active", session.getCurrentState());
        assertTrue(mediaBridge.isJoined("leg_cust_1", "leg_agent_1"));
        assertEquals(DuplexMode.FULL, mediaBridge.getDuplex("leg_cust_1", "leg_agent_1"));

        // 2. Supervisor initiates coaching
        String supervisorLeg = "leg_supervisor_9";
        session.processEvent(new CcxmlEvent("supervisor.join", supervisorLeg));
        assertEquals("whisper_coaching_active", session.getCurrentState());

        // Verify half duplex join: supervisor audio to agent
        assertTrue(mediaBridge.isJoined(supervisorLeg, "leg_agent_1"));
        assertEquals(DuplexMode.HALF, mediaBridge.getDuplex(supervisorLeg, "leg_agent_1"));

        // Verify customer is NOT joined directly to supervisor with full duplex
        assertFalse(mediaBridge.isJoined(supervisorLeg, "leg_cust_1") &&
                mediaBridge.getDuplex(supervisorLeg, "leg_cust_1") == DuplexMode.FULL);

        // 3. Supervisor leaves coaching
        session.processEvent(new CcxmlEvent("supervisor.leave", supervisorLeg));
        assertEquals("call_active", session.getCurrentState());
        assertFalse(mediaBridge.isJoined(supervisorLeg, "leg_agent_1"));
        // Customer and agent call remains intact
        assertTrue(mediaBridge.isJoined("leg_cust_1", "leg_agent_1"));
    }

    @Test
    @DisplayName("Scenario D: Outbound Campaign Dialing with VoiceXML Dialog and Disconnect")
    void testScenarioD_OutboundCampaignDialing() {
        String ccxml = """
            <ccxml version="1.0">
                <var name="campaignLeg"/>
                <var name="surveyDialog"/>

                <eventprocessor statevariable="campaignState">
                    <!-- 1. Initiate outbound campaign call -->
                    <transition event="campaign.start" state="init">
                        <createcall dest="'sip:+15552345678@pstn.gateway'"
                                    callerid="'18005550100'"
                                    connectionid="campaignLeg"
                                    timeout="'30s'"/>
                        <assign name="campaignState" expr="'dialing'"/>
                    </transition>

                    <!-- 2. Callee answers -> launch VoiceXML automated survey dialog -->
                    <transition event="connection.connected" state="dialing">
                        <dialogstart src="'http://media/campaign_survey.vxml'"
                                     dialogid="surveyDialog"
                                     connectionid="campaignLeg"/>
                        <assign name="campaignState" expr="'survey_in_progress'"/>
                    </transition>

                    <!-- 3. Survey completes -> disconnect and terminate session -->
                    <transition event="dialog.exit" state="survey_in_progress">
                        <disconnect connectionid="campaignLeg"/>
                        <exit/>
                    </transition>

                    <!-- If callee does not answer or busy -->
                    <transition event="connection.failed" state="dialing">
                        <exit/>
                    </transition>
                </eventprocessor>
            </ccxml>
            """;

        CcxmlSession session = createSession(ccxml);

        // 1. Start campaign
        session.processEvent(new CcxmlEvent("campaign.start"));
        assertEquals("dialing", session.getCurrentState());
        assertNotNull(session.getVariable("campaignLeg"));
        String campaignConnId = (String) session.getVariable("campaignLeg");
        assertTrue(connectionBridge.getCallLog().stream().anyMatch(log -> log.contains("sip:+15552345678@pstn.gateway")));

        // 2. Customer answers
        session.processEvent(new CcxmlEvent("connection.connected", campaignConnId));
        assertEquals("survey_in_progress", session.getCurrentState());
        assertNotNull(session.getVariable("surveyDialog"));
        assertTrue(dialogBridge.getDialogLog().stream().anyMatch(log -> log.contains("campaign_survey.vxml")));

        // 3. Survey finished
        session.processEvent(new CcxmlEvent("dialog.exit", campaignConnId, "dlg_survey", null, Map.of("score", 5)));
        assertTrue(session.isTerminated());
        assertTrue(connectionBridge.getCallLog().contains("DISCONNECT:" + campaignConnId + ":null"));
    }
}
