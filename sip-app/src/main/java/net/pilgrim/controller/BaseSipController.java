package net.pilgrim.controller;

import io.micronaut.core.annotation.Nullable;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.transport.SipNettyServer;

/**
 * Common base class for SIP controllers, providing transport resolution,
 * advertised IP resolution, and Contact URI construction.
 */
public abstract class BaseSipController {

    protected final SipNettyServer sipServer;
    protected final SipServerConfiguration serverConfig;

    protected BaseSipController() {
        this(null, null);
    }

    protected BaseSipController(@Nullable SipNettyServer sipServer,
                                @Nullable SipServerConfiguration serverConfig) {
        this.sipServer = sipServer;
        this.serverConfig = serverConfig != null ? serverConfig
                : (sipServer != null ? sipServer.getConfiguration() : new SipServerConfiguration());
    }

    public String resolveAdvertisedIp() {
        if (serverConfig != null) {
            return serverConfig.resolveAdvertisedIp();
        }
        if (sipServer != null) {
            return sipServer.getAdvertisedIp();
        }
        return "127.0.0.1";
    }

    public int resolveServerPort(SipRequest request) {
        if (sipServer != null && sipServer.getTransportRegistry() != null) {
            return sipServer.getTransportRegistry().resolveServerPort(request, serverConfig);
        }
        boolean isTcp = request != null && request.getTransport() == SipTransport.TCP;
        if (isTcp) {
            return sipServer != null ? sipServer.getTcpPort()
                    : (serverConfig != null ? serverConfig.getTcpPort() : 5060);
        } else {
            return sipServer != null ? sipServer.getUdpPort()
                    : (serverConfig != null ? serverConfig.getUdpPort() : 5060);
        }
    }

    public String buildContactUri(SipRequest request) {
        String advertisedIp = resolveAdvertisedIp();
        int serverPort = resolveServerPort(request);
        if (sipServer != null && sipServer.getTransportRegistry() != null) {
            return sipServer.getTransportRegistry().formatContactUri(request, advertisedIp, serverPort);
        }
        boolean isTcp = request != null && request.getTransport() == SipTransport.TCP;
        return "<sip:" + advertisedIp + ":" + serverPort + (isTcp ? ";transport=tcp" : "") + ">";
    }
}
