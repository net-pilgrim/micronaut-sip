package net.pilgrim.ccxml;

import net.pilgrim.ccxml.ast.CcxmlDocument;
import net.pilgrim.ccxml.bridge.InMemoryConnectionBridge;
import net.pilgrim.ccxml.bridge.InMemoryDialogBridge;
import net.pilgrim.ccxml.bridge.InMemoryMediaBridge;
import net.pilgrim.ccxml.parser.CcxmlParser;
import net.pilgrim.ccxml.runtime.CcxmlEvent;
import net.pilgrim.ccxml.runtime.CcxmlSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MicroservicesScenarioTest {

    @Test
    @DisplayName("Verify Microservices Orchestration CCXML Scenario")
    void testMicroservicesCcxmlScenario() {
        String ccxml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ccxml version="1.0" xmlns="http://www.w3.org/2002/09/ccxml">
                <!-- ============================================================== -->
                <!-- State Variables & Multi-Container Endpoint Definitions          -->
                <!-- ============================================================== -->
                <var name="callerLeg"/>
                <var name="msmlLeg"/>
                <var name="vxmlDialog"/>
                <var name="operatorLeg"/>
                
                <!-- Service Discovery URIs for Individual Microservice Containers -->
                <var name="msmlServiceUri" expr="'sip:msml@msml-media-server.internal:5060'"/>
                <var name="vxmlServiceUri" expr="'http://vxml-interpreter.internal:8080/voicemail.vxml'"/>
                <var name="mrcpServiceUri" expr="'mrcp://mrcp-speech-server.internal:4900'"/>

                <eventprocessor statevariable="callState">

                    <!-- ========================================================== -->
                    <!-- 1. Inbound Call Arrival from SIP Proxy                      -->
                    <!-- ========================================================== -->
                    <transition event="connection.alerting" state="init">
                        <assign name="callerLeg" expr="event$.connectionid"/>
                        <!-- Accept inbound caller session -->
                        <accept connectionid="callerLeg"/>
                        <assign name="callState" expr="'caller_accepted'"/>
                    </transition>

                    <!-- ========================================================== -->
                    <!-- 2. Caller Connected -> Invite MSML Media Streamer Leg       -->
                    <!-- ========================================================== -->
                    <transition event="connection.connected" state="caller_accepted">
                        <!-- Invite MSML media server container to allocate RTP pipeline -->
                        <createcall dest="msmlServiceUri" connectionid="msmlLeg"/>
                        <assign name="callState" expr="'connecting_msml'"/>
                    </transition>

                    <!-- ========================================================== -->
                    <!-- 3. MSML Leg Ready -> Join RTP & Start VXML Dialog          -->
                    <!-- ========================================================== -->
                    <transition event="connection.connected" state="connecting_msml">
                        <!-- Join Caller RTP directly to MSML Media Server (full duplex) -->
                        <join id1="callerLeg" id2="msmlLeg" duplex="full"/>
                        
                        <!-- Delegate call logic to VXML interpreter container -->
                        <!-- Passes MRCP speech endpoint and MSML control context -->
                        <dialogstart src="vxmlServiceUri" 
                                     dialogid="vxmlDialog" 
                                     connectionid="callerLeg"
                                     namelist="mrcpServiceUri msmlLeg"/>
                        <assign name="callState" expr="'dialog_active'"/>
                    </transition>

                    <!-- ========================================================== -->
                    <!-- 4. Dialog Events: Operator Escalation                      -->
                    <!-- ========================================================== -->
                    <transition event="dialog.user.transfer" state="dialog_active">
                        <!-- Caller requested live operator in VoiceXML dialog -->
                        <unjoin id1="callerLeg" id2="msmlLeg"/>
                        <disconnect connectionid="msmlLeg"/>
                        <createcall dest="event$.values.target" connectionid="operatorLeg"/>
                        <assign name="callState" expr="'calling_operator'"/>
                    </transition>

                    <transition event="connection.connected" state="calling_operator">
                        <!-- Bridge caller directly to human operator -->
                        <join id1="callerLeg" id2="operatorLeg" duplex="full"/>
                        <assign name="callState" expr="'operator_connected'"/>
                    </transition>

                    <!-- ========================================================== -->
                    <!-- 5. Dialog Finished Normally (Voicemail Saved)              -->
                    <!-- ========================================================== -->
                    <transition event="dialog.exit" state="dialog_active">
                        <!-- VXML completed execution -->
                        <unjoin id1="callerLeg" id2="msmlLeg"/>
                        <disconnect connectionid="callerLeg"/>
                        <disconnect connectionid="msmlLeg"/>
                        <assign name="callState" expr="'completed'"/>
                        <exit/>
                    </transition>

                    <!-- ========================================================== -->
                    <!-- 6. Teardown / Mid-Call Disconnect Handling                 -->
                    <!-- ========================================================== -->
                    <transition event="connection.disconnected">
                        <dialogterminate dialogid="vxmlDialog"/>
                        <disconnect connectionid="callerLeg"/>
                        <disconnect connectionid="msmlLeg"/>
                        <assign name="callState" expr="'terminated'"/>
                        <exit/>
                    </transition>

                    <!-- Catch-all error recovery -->
                    <transition event="error.*">
                        <disconnect connectionid="callerLeg"/>
                        <disconnect connectionid="msmlLeg"/>
                        <exit/>
                    </transition>

                </eventprocessor>
            </ccxml>
            """;

        CcxmlParser parser = new CcxmlParser();
        CcxmlDocument doc = parser.parse(ccxml);
        assertNotNull(doc);
        assertEquals("1.0", doc.version());

        InMemoryConnectionBridge connBridge = new InMemoryConnectionBridge();
        InMemoryDialogBridge dialogBridge = new InMemoryDialogBridge();
        InMemoryMediaBridge mediaBridge = new InMemoryMediaBridge();

        CcxmlSession session = new CcxmlSession("test-microservices", doc, connBridge, dialogBridge, mediaBridge);
        assertEquals("init", session.getCurrentState());

        // Step 1: Inbound INVITE arrives from SIP Proxy
        session.processEvent(new CcxmlEvent("connection.alerting", "conn_caller_101"));
        assertEquals("caller_accepted", session.getCurrentState());
        assertEquals("conn_caller_101", session.getVariable("callerLeg"));
        assertTrue(connBridge.getCallLog().contains("ACCEPT:conn_caller_101"));

        // Step 2: Caller connected -> triggers MSML container invite
        session.processEvent(new CcxmlEvent("connection.connected", "conn_caller_101"));
        assertEquals("connecting_msml", session.getCurrentState());
        String msmlLegId = (String) session.getVariable("msmlLeg");
        assertNotNull(msmlLegId);
        assertTrue(connBridge.getCallLog().stream().anyMatch(log -> log.contains("CREATE_CALL") && log.contains("msml-media-server.internal")));

        // Step 3: MSML container connects -> join RTP and start VXML dialog
        session.processEvent(new CcxmlEvent("connection.connected", msmlLegId));
        assertEquals("dialog_active", session.getCurrentState());
        assertTrue(mediaBridge.isJoined("conn_caller_101", msmlLegId));
        assertNotNull(session.getVariable("vxmlDialog"));

        // Step 4: Dialog completes (voicemail saved) -> disconnect & exit
        session.processEvent(new CcxmlEvent("dialog.exit", null, (String) session.getVariable("vxmlDialog"), null, Map.of("status", "saved")));
        assertEquals("completed", session.getCurrentState());
        assertTrue(connBridge.getCallLog().stream().anyMatch(log -> log.startsWith("DISCONNECT:conn_caller_101")));
        assertTrue(connBridge.getCallLog().stream().anyMatch(log -> log.startsWith("DISCONNECT:" + msmlLegId)));
    }
}
