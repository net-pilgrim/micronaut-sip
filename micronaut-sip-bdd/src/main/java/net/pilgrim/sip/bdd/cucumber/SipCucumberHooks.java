package net.pilgrim.sip.bdd.cucumber;

import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.Scenario;
import net.pilgrim.sip.bdd.model.ScenarioContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Cucumber scenario lifecycle hooks managing context isolation, diagnostic reporting,
 * and embedded SIP server lifecycle.
 */
public class SipCucumberHooks {

    private static final Logger LOG = LoggerFactory.getLogger(SipCucumberHooks.class);
    private static final SipServerManager serverManager = SipServerManager.getInstance();

    private final ScenarioContext context;

    public SipCucumberHooks() {
        this.context = ScenarioContext.current();
    }

    public SipCucumberHooks(ScenarioContext context) {
        this.context = context != null ? context : ScenarioContext.current();
    }

    /**
     * Starts the embedded Micronaut SIP server once before all scenarios.
     * The server will be available at the address returned by serverManager.getServerAddress().
     */
    @BeforeAll
    public static void startServer() {
        LOG.info("=== Starting embedded SIP server for BDD test suite ===");
        serverManager.startServer();
        InetSocketAddress address = serverManager.getServerAddress();
        LOG.info("=== SIP server ready at {}:{} ===", address.getHostString(), address.getPort());
    }

    /**
     * Stops the embedded SIP server after all scenarios complete.
     */
    @AfterAll
    public static void stopServer() {
        LOG.info("=== Stopping embedded SIP server ===");
        serverManager.stopServer();
    }

    @Before
    public void setUp(Scenario scenario) {
        LOG.info("=== Starting Scenario: {} ===", scenario.getName());
        context.reset();

        // Set the SUT address to the embedded server for all scenarios
        // (can be overridden by "Given the SIP server under test is at" step)
        if (serverManager.isStarted()) {
            context.setSutAddress(serverManager.getServerAddress());
            LOG.debug("Default SUT address set to embedded server: {}", serverManager.getServerAddress());
        }
    }

    @After
    public void tearDown(Scenario scenario) {
        if (scenario.isFailed()) {
            String mermaid = context.getRecorder().generateMermaidDiagram();
            LOG.error("=== Scenario FAILED: {} ===", scenario.getName());
            LOG.error("Captured SIP Call Flow:\n{}", mermaid);
            scenario.log("Captured SIP Call Flow:\n" + mermaid);
        } else {
            LOG.info("=== Scenario PASSED: {} ===", scenario.getName());
        }

        context.reset();
    }
}
