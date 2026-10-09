package net.pilgrim.controller;

import io.micronaut.core.annotation.Nullable;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipTransport;
import net.pilgrim.sip.transport.SipNettyServer;

import java.util.Optional;

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
        this.serverConfig = Optional.ofNullable(serverConfig)
                .or(() -> Optional.ofNullable(sipServer).map(SipNettyServer::getConfiguration))
                .orElseGet(SipServerConfiguration::new);
    }

    public String resolveAdvertisedIp() {
        return Optional.ofNullable(serverConfig)
                .map(SipServerConfiguration::resolveAdvertisedIp)
                .or(() -> Optional.ofNullable(sipServer).map(SipNettyServer::getAdvertisedIp))
                .orElse("127.0.0.1");
    }

    public int resolveServerPort(SipRequest request) {
        return Optional.ofNullable(sipServer)
                .map(SipNettyServer::getTransportRegistry)
                .map(reg -> reg.resolveServerPort(request, serverConfig))
                .orElseGet(() -> {
                    boolean isTcp = Optional.ofNullable(request)
                            .map(SipRequest::getTransport)
                            .filter(t -> t == SipTransport.TCP)
                            .isPresent();
                    if (isTcp) {
                        return Optional.ofNullable(sipServer)
                                .map(SipNettyServer::getTcpPort)
                                .or(() -> Optional.ofNullable(serverConfig).map(SipServerConfiguration::getTcpPort))
                                .orElse(5060);
                    }
                    return Optional.ofNullable(sipServer)
                            .map(SipNettyServer::getUdpPort)
                            .or(() -> Optional.ofNullable(serverConfig).map(SipServerConfiguration::getUdpPort))
                            .orElse(5060);
                });
    }

    public String buildContactUri(SipRequest request) {
        String advertisedIp = resolveAdvertisedIp();
        int serverPort = resolveServerPort(request);
        return Optional.ofNullable(sipServer)
                .map(SipNettyServer::getTransportRegistry)
                .map(reg -> reg.formatContactUri(request, advertisedIp, serverPort))
                .orElseGet(() -> {
                    boolean isTcp = Optional.ofNullable(request)
                            .map(SipRequest::getTransport)
                            .filter(t -> t == SipTransport.TCP)
                            .isPresent();
                    return "<sip:" + advertisedIp + ":" + serverPort + (isTcp ? ";transport=tcp" : "") + ">";
                });
    }
}
