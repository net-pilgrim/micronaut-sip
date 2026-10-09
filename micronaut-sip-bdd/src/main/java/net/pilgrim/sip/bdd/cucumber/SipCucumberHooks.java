package net.pilgrim.sip.bdd.cucumber;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import net.pilgrim.sip.bdd.model.ScenarioContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cucumber scenario lifecycle hooks managing context isolation and diagnostic reporting.
 */
public class SipCucumberHooks {

    private static final Logger LOG = LoggerFactory.getLogger(SipCucumberHooks.class);

    private final ScenarioContext context;

    public SipCucumberHooks() {
        this.context = ScenarioContext.current();
    }

    public SipCucumberHooks(ScenarioContext context) {
        this.context = context != null ? context : ScenarioContext.current();
    }

    @Before
    public void setUp(Scenario scenario) {
        LOG.info("=== Starting Scenario: {} ===", scenario.getName());
        context.reset();
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
