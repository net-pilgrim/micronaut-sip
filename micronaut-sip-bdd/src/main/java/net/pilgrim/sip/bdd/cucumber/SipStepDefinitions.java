package net.pilgrim.sip.bdd.cucumber;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.pilgrim.sip.bdd.engine.VirtualUserAgentManager;
import net.pilgrim.sip.bdd.matcher.SipPredicates;
import net.pilgrim.sip.bdd.model.DialogState;
import net.pilgrim.sip.bdd.model.ScenarioContext;
import net.pilgrim.sip.bdd.model.VirtualUserAgent;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.sdp.SdpMessage;
import net.pilgrim.sip.sdp.SdpParser;
import org.assertj.core.api.Assertions;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cucumber Step Definitions for Gherkin-based SIP verification.
 */
public class SipStepDefinitions {

    private final ScenarioContext context;
    private final VirtualUserAgentManager agentManager = new VirtualUserAgentManager();
    private final SdpParser sdpParser = new SdpParser();

    public SipStepDefinitions() {
        this.context = ScenarioContext.current();
    }

    public SipStepDefinitions(ScenarioContext context) {
        this.context = context != null ? context : ScenarioContext.current();
    }

    // ==========================================
    // Given Steps
    // ==========================================

    @Given("a SIP endpoint {string}")
    public void givenEndpoint(String actorName) {
        agentManager.createEndpoint(actorName, "127.0.0.1", 0, context);
    }

    @Given("a SIP endpoint {string} at {string}")
    public void givenEndpointAt(String actorName, String uriOrAddress) {
        String interpolated = context.interpolate(uriOrAddress);
        int port = 0;
        String host = "127.0.0.1";

        if (interpolated.startsWith("sip:")) {
            try {
                SipUri uri = SipUri.parse(interpolated);
                if (uri.getPort() > 0) {
                    port = uri.getPort();
                }
                if (uri.getHost() != null) {
                    host = uri.getHost();
                }
            } catch (Exception ignored) {}
        } else if (interpolated.contains(":")) {
            String[] parts = interpolated.split(":");
            host = parts[0];
            port = Integer.parseInt(parts[1]);
        }

        agentManager.createEndpoint(actorName, host, port, context);
    }

    @Given("a SIP endpoint {string} listening on port {int}")
    public void givenEndpointOnPort(String actorName, int port) {
        agentManager.createEndpoint(actorName, "127.0.0.1", port, context);
    }

    @Given("a SIP endpoint {string} with username {string} and password {string}")
    public void givenEndpointWithCredentials(String actorName, String username, String password) {
        VirtualUserAgent agent = context.getActor(actorName);
        if (agent == null) {
            agent = agentManager.createEndpoint(actorName, "127.0.0.1", 0, context);
        }
        agent.setCredentials(username, password);
    }

    @Given("the SIP server under test is at {string}")
    public void givenSutAt(String hostPort) {
        String interpolated = context.interpolate(hostPort);
        String[] parts = interpolated.split(":");
        InetSocketAddress addr = new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
        context.setSutAddress(addr);
    }

    // ==========================================
    // When Steps: Sending Requests & Responses
    // ==========================================

    @When("{string} sends an {string} to {string}")
    public void sendsRequest(String senderName, String methodName, String recipientName) {
        sendsRequestInternal(senderName, methodName, recipientName, null, null);
    }

    @When("{string} sends a {string} to {string}")
    public void sendsRequestA(String senderName, String methodName, String recipientName) {
        sendsRequestInternal(senderName, methodName, recipientName, null, null);
    }

    @When("{string} sends an {string} to {string} with headers:")
    public void sendsRequestWithHeaders(String senderName, String methodName, String recipientName, DataTable dataTable) {
        sendsRequestInternal(senderName, methodName, recipientName, dataTable.asMaps(), null);
    }

    @When("{string} sends a {string} to {string} with headers:")
    public void sendsRequestWithHeadersA(String senderName, String methodName, String recipientName, DataTable dataTable) {
        sendsRequestInternal(senderName, methodName, recipientName, dataTable.asMaps(), null);
    }

    @When("{string} sends an {string} to {string} with body {string}")
    public void sendsRequestWithBody(String senderName, String methodName, String recipientName, String body) {
        sendsRequestInternal(senderName, methodName, recipientName, null, context.interpolate(body));
    }

    @When("{string} sends an {string} to {string} with SDP offer:")
    public void sendsInviteWithSdp(String senderName, String methodName, String recipientName, DataTable dataTable) {
        VirtualUserAgent sender = context.requireActor(senderName);
        String sdp = buildSdpFromTable(dataTable, sender.getBoundAddress().getHostString(), sender.getPort() + 1000);
        sendsRequestInternal(senderName, methodName, recipientName,
                List.of(Map.of("Header-Name", "Content-Type", "Value", "application/sdp")), sdp);
    }

    private void sendsRequestInternal(String senderName, String methodName, String recipientName,
                                      List<Map<String, String>> headers, String body) {
        VirtualUserAgent sender = context.requireActor(senderName);
        VirtualUserAgent recipient = context.getActor(recipientName);

        InetSocketAddress dest;
        String targetUri;

        if (recipient != null) {
            dest = recipient.getBoundAddress();
            targetUri = recipient.getSipUri();
        } else if (context.getSutAddress() != null) {
            dest = context.getSutAddress();
            targetUri = "sip:" + recipientName + "@" + dest.getHostString() + ":" + dest.getPort();
        } else {
            throw new IllegalArgumentException("Unknown recipient or SUT destination: " + recipientName);
        }

        SipMethod method = SipMethod.valueOf(methodName.trim().toUpperCase());
        SipRequest.Builder builder = SipRequest.builder(method, targetUri)
                .from("<" + sender.getSipUri() + ">")
                .to("<" + targetUri + ">")
                .callId(context.getOrCreateCallId())
                .contact(sender.getContactHeader());

        if (headers != null) {
            for (Map<String, String> row : headers) {
                String name = row.get("Header-Name");
                if (name == null) name = row.get("header");
                String val = row.get("Value");
                if (val == null) val = row.get("value");
                if (name != null && val != null) {
                    builder.header(name.trim(), context.interpolate(val.trim()));
                }
            }
        }

        if (body != null) {
            builder.body(body);
        }

        SipRequest request = builder.build();
        sender.sendRequest(request, dest, recipientName);
    }

    @When("{string} responds with {string}")
    public void respondsWith(String responderName, String statusStr) {
        respondsWithInternal(responderName, statusStr, null, null);
    }

    @When("{string} responds with {string} with headers:")
    public void respondsWithAndHeaders(String responderName, String statusStr, DataTable dataTable) {
        respondsWithInternal(responderName, statusStr, dataTable.asMaps(), null);
    }

    @When("{string} responds with {string} with SDP answer:")
    public void respondsWithAndSdp(String responderName, String statusStr, DataTable dataTable) {
        VirtualUserAgent responder = context.requireActor(responderName);
        String sdp = buildSdpFromTable(dataTable, responder.getBoundAddress().getHostString(), responder.getPort() + 1000);
        respondsWithInternal(responderName, statusStr,
                List.of(Map.of("Header-Name", "Content-Type", "Value", "application/sdp")), sdp);
    }

    private void respondsWithInternal(String responderName, String statusStr,
                                      List<Map<String, String>> headers, String body) {
        VirtualUserAgent responder = context.requireActor(responderName);
        SipRequest incoming = responder.getLastReceivedRequest() != null
                ? responder.getLastReceivedRequest()
                : (responder.getLastReceivedInvite() != null ? responder.getLastReceivedInvite() : context.getLastRequest());

        if (incoming == null) {
            throw new IllegalStateException("Cannot respond: no received request found on actor '" + responderName + "'");
        }

        int statusCode = SipPredicates.parseStatusCode(statusStr);
        String reason = SipStatus.getReasonPhrase(statusCode);
        SipResponse response = incoming.createResponse(statusCode, reason);

        if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
            response.getHeaders().setContact(responder.getContactHeader());
        }

        if (headers != null) {
            for (Map<String, String> row : headers) {
                String name = row.get("Header-Name");
                if (name == null) name = row.get("header");
                String val = row.get("Value");
                if (val == null) val = row.get("value");
                if (name != null && val != null) {
                    response.getHeaders().set(name.trim(), context.interpolate(val.trim()));
                }
            }
        }

        if (body != null) {
            response.setBody(body);
        }

        InetSocketAddress dest = response.getRemoteAddress() != null
                ? response.getRemoteAddress()
                : incoming.getRemoteAddress();

        responder.sendResponse(response, dest, "Peer");
    }

    @When("{string} sends an ACK to {string}")
    public void sendsAck(String senderName, String recipientName) {
        VirtualUserAgent sender = context.requireActor(senderName);
        VirtualUserAgent recipient = context.requireActor(recipientName);
        sender.sendAck(recipient);
    }

    @When("{string} sends a BYE to {string}")
    public void sendsBye(String senderName, String recipientName) {
        VirtualUserAgent sender = context.requireActor(senderName);
        VirtualUserAgent recipient = context.requireActor(recipientName);
        sender.sendBye(recipient);
    }

    @When("{string} sends PRACK acknowledging the provisional response to {string}")
    public void sendsPrack(String senderName, String recipientName) {
        VirtualUserAgent sender = context.requireActor(senderName);
        VirtualUserAgent recipient = context.requireActor(recipientName);
        sender.sendPrack(recipient);
    }

    @When("{string} retries the last request with valid digest credentials")
    public void retriesWithAuth(String senderName) {
        VirtualUserAgent sender = context.requireActor(senderName);
        SipResponse challenge = context.getLastResponse();
        if (challenge == null) {
            throw new IllegalStateException("No challenge response found in scenario context");
        }

        InetSocketAddress dest = challenge.getRemoteAddress() != null
                ? challenge.getRemoteAddress()
                : context.getSutAddress();

        sender.retryWithAuth(challenge, dest, "SUT");
    }

    // ==========================================
    // Then Steps: Assertions
    // ==========================================

    @Then("{string} receives {string} within {duration}")
    public void receivesStatusWithin(String recipientName, String statusStr, Duration timeout) {
        VirtualUserAgent recipient = context.requireActor(recipientName);
        int statusCode = SipPredicates.parseStatusCode(statusStr);

        SipMessage msg = recipient.getMailbox()
                .awaitMessage(SipPredicates.isResponse(statusCode), timeout)
                .block();

        Assertions.assertThat(msg).isNotNull();
        Assertions.assertThat(msg).isInstanceOf(SipResponse.class);
        context.setLastReceivedMessage(msg);
    }

    @Then("{string} receives an {string} request within {duration}")
    public void receivesRequestWithin(String recipientName, String methodName, Duration timeout) {
        VirtualUserAgent recipient = context.requireActor(recipientName);

        SipMessage msg = recipient.getMailbox()
                .awaitMessage(SipPredicates.isRequest(methodName), timeout)
                .block();

        Assertions.assertThat(msg).isNotNull();
        Assertions.assertThat(msg).isInstanceOf(SipRequest.class);
        context.setLastReceivedMessage(msg);
    }

    @Then("{string} receives a {string} request within {duration}")
    public void receivesRequestWithinA(String recipientName, String methodName, Duration timeout) {
        receivesRequestWithin(recipientName, methodName, timeout);
    }

    @Then("{string} receives no messages within {duration}")
    public void receivesNoMessagesWithin(String recipientName, Duration duration) {
        VirtualUserAgent recipient = context.requireActor(recipientName);
        recipient.getMailbox().assertQuietPeriod(msg -> true, duration).block();
    }

    @Then("header {string} equals {string}")
    public void headerEquals(String headerName, String expectedValue) {
        SipMessage msg = context.getLastReceivedMessage();
        Assertions.assertThat(msg).as("No received message in context").isNotNull();
        String val = msg.getHeaders().get(headerName);
        Assertions.assertThat(val).as("Header %s", headerName).isEqualTo(context.interpolate(expectedValue));
    }

    @Then("header {string} matches regex {string}")
    public void headerMatchesRegex(String headerName, String regex) {
        SipMessage msg = context.getLastReceivedMessage();
        Assertions.assertThat(msg).as("No received message in context").isNotNull();
        String val = msg.getHeaders().get(headerName);
        Assertions.assertThat(val).as("Header %s", headerName).isNotNull();
        Assertions.assertThat(Pattern.compile(regex).matcher(val).find())
                .as("Header '%s' with value '%s' did not match regex '%s'", headerName, val, regex)
                .isTrue();
    }

    @Then("header {string} contains parameter {string} with value {string}")
    public void headerContainsParam(String headerName, String paramName, String paramValue) {
        SipMessage msg = context.getLastReceivedMessage();
        Assertions.assertThat(msg).as("No received message in context").isNotNull();
        String val = msg.getHeaders().get(headerName);
        Assertions.assertThat(val).as("Header %s", headerName).isNotNull();
        String expected = paramName + "=" + context.interpolate(paramValue);
        Assertions.assertThat(val).contains(expected);
    }

    @Then("header {string} contains parameter {string}")
    public void headerContainsParamOnly(String headerName, String paramName) {
        SipMessage msg = context.getLastReceivedMessage();
        Assertions.assertThat(msg).as("No received message in context").isNotNull();
        String val = msg.getHeaders().get(headerName);
        Assertions.assertThat(val).as("Header %s", headerName).isNotNull();
        Assertions.assertThat(val).contains(paramName);
    }

    @Then("the dialog between {string} and {string} is in state {string}")
    public void dialogStateCheck(String actor1Name, String actor2Name, String stateStr) {
        VirtualUserAgent a1 = context.requireActor(actor1Name);
        VirtualUserAgent a2 = context.requireActor(actor2Name);
        DialogState expected = DialogState.valueOf(stateStr.trim().toUpperCase());

        Assertions.assertThat(a1.getDialogState())
                .as("Dialog state of %s", actor1Name)
                .isEqualTo(expected);
        Assertions.assertThat(a2.getDialogState())
                .as("Dialog state of %s", actor2Name)
                .isEqualTo(expected);
    }

    @Then("{string} captures header {string} as {string}")
    public void capturesHeaderAs(String actorName, String headerName, String varName) {
        SipMessage msg = context.getLastReceivedMessage();
        Assertions.assertThat(msg).as("No received message in context").isNotNull();
        String val = msg.getHeaders().get(headerName);
        Assertions.assertThat(val).as("Header %s", headerName).isNotNull();
        context.setVariable(varName, val);
    }

    @Then("SDP negotiated codec is {string}")
    public void sdpNegotiatedCodec(String expectedCodec) {
        SipMessage msg = context.getLastReceivedMessage();
        Assertions.assertThat(msg).as("No received message in context").isNotNull();
        String body = msg.getBodyAsString();
        Assertions.assertThat(body).as("SDP body is null").isNotBlank();

        SdpMessage sdp = sdpParser.parse(body);
        SdpMessage.MediaDescription audio = sdp.findFirstAudioMedia();
        Assertions.assertThat(audio).as("Audio media in SDP").isNotNull();

        boolean matched = audio.getAttributes().stream().anyMatch(a -> a.contains(expectedCodec))
                || audio.getFormats().stream().anyMatch(f -> f.equalsIgnoreCase(expectedCodec));
        Assertions.assertThat(matched)
                .as("SDP attributes '%s' did not contain codec '%s'", audio.getAttributes(), expectedCodec)
                .isTrue();
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    private String buildSdpFromTable(DataTable table, String host, int defaultPort) {
        SdpMessage sdp = new SdpMessage();
        sdp.setOrigin("- 0 0 IN IP4 " + host);
        sdp.setConnection("IN IP4 " + host);

        for (Map<String, String> row : table.asMaps()) {
            String media = row.getOrDefault("media", "audio");
            int port = row.containsKey("port") ? Integer.parseInt(row.get("port")) : defaultPort;
            String proto = row.getOrDefault("proto", "RTP/AVP");
            String codec = row.getOrDefault("codec", "PCMU");

            String formatPayload = "0";
            if (codec.equalsIgnoreCase("PCMA")) formatPayload = "8";
            else if (codec.equalsIgnoreCase("telephone-event")) formatPayload = "101";

            SdpMessage.MediaDescription md = new SdpMessage.MediaDescription(media, port, proto, List.of(formatPayload));
            md.addAttribute("rtpmap:" + formatPayload + " " + codec + "/8000");
            sdp.addMediaDescription(md);
        }

        return sdp.toSdpString();
    }
}
