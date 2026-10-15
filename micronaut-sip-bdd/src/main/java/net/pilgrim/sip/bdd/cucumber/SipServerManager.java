package net.pilgrim.sip.bdd.cucumber;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import net.pilgrim.sip.transport.SipNettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Manages the lifecycle of an embedded Micronaut SIP server for BDD testing.
 * Ensures the real sip-app server is running so VirtualUserAgents can test against it.
 */
public class SipServerManager {

    private static final Logger LOG = LoggerFactory.getLogger(SipServerManager.class);
    private static SipServerManager instance;

    private ApplicationContext applicationContext;
    private SipNettyServer sipServer;
    private InetSocketAddress serverAddress;
    private boolean started = false;

    private SipServerManager() {
    }

    public static synchronized SipServerManager getInstance() {
        if (instance == null) {
            instance = new SipServerManager();
        }
        return instance;
    }

    /**
     * Starts the embedded Micronaut SIP server if not already started.
     * Uses ephemeral ports (0) to avoid conflicts.
     */
    public synchronized void startServer() {
        if (started) {
            LOG.debug("SIP server already started at {}", serverAddress);
            return;
        }

        LOG.info("Starting embedded Micronaut SIP server for BDD tests...");

        try {
            Map<String, Object> properties = new HashMap<>();
            properties.put("micronaut.application.name", "sip-bdd-test");
            properties.put("sip.server.enabled", "true");
            properties.put("sip.server.udp-enabled", "true");
            properties.put("sip.server.udp.host", "127.0.0.1");
            properties.put("sip.server.udp.port", "0"); // Ephemeral port
            properties.put("sip.server.tcp-enabled", "false");
            properties.put("sip.server.tls-enabled", "false");
            properties.put("sip.server.advertised-ip", "127.0.0.1");
            properties.put("sip.server.request-timeout-ms", "3000");

            // Disable non-essential features for faster startup
            properties.put("netann.call.max-active-announcements", "10");
            properties.put("mailbox.enabled", "false");
            properties.put("mrcp.server.enabled", "false");
            properties.put("sgw.enabled", "false");

            // RTP port range for media tests
            properties.put("sip.rtp.port-range-start", "30000");
            properties.put("sip.rtp.port-range-end", "30100");

            applicationContext = ApplicationContext.run(properties);

            // Get the SIP server bean and extract bound address
            sipServer = applicationContext.getBean(SipNettyServer.class);

            if (sipServer != null) {
                serverAddress = sipServer.getBoundAddress();
                if (serverAddress == null) {
                    // Fallback if getBoundAddress returns null
                    int actualPort = sipServer.getPort();
                    serverAddress = new InetSocketAddress("127.0.0.1", actualPort);
                }
                started = true;
                LOG.info("✓ SIP server started successfully at {}:{}",
                        serverAddress.getHostString(), serverAddress.getPort());
            } else {
                throw new IllegalStateException("SipNettyServer bean not found in context");
            }

        } catch (Exception e) {
            LOG.error("Failed to start embedded SIP server", e);
            throw new RuntimeException("Could not start SIP server for BDD tests", e);
        }
    }

    /**
     * Stops the embedded SIP server and cleans up resources.
     */
    public synchronized void stopServer() {
        if (!started) {
            return;
        }

        LOG.info("Stopping embedded Micronaut SIP server...");
        try {
            if (applicationContext != null && applicationContext.isRunning()) {
                applicationContext.stop();
                applicationContext.close();
            }
            started = false;
            sipServer = null;
            serverAddress = null;
            LOG.info("✓ SIP server stopped successfully");
        } catch (Exception e) {
            LOG.error("Error stopping SIP server", e);
        }
    }

    public InetSocketAddress getServerAddress() {
        if (!started) {
            throw new IllegalStateException("SIP server not started. Call startServer() first.");
        }
        return serverAddress;
    }

    public boolean isStarted() {
        return started;
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    public SipNettyServer getSipServer() {
        return sipServer;
    }
}
