package net.pilgrim.sip.client;

import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.dtmf.DtmfSignal;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.transport.SipNettyServer;
import net.pilgrim.sip.transport.SipResponseRouter;
import net.pilgrim.sip.transport.SipStreamFrameDecoder;
import net.pilgrim.sip.transport.SipStreamEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * High-level reactive client for sending SIP requests over UDP or TCP.
 */
@Singleton
public class ReactiveSipClient {

    private static final Logger LOG = LoggerFactory.getLogger(ReactiveSipClient.class);

    private final SipNettyServer server;
    private final SipResponseRouter responseRouter;
    private final SipServerConfiguration configuration;
    private final AtomicLong cseqCounter = new AtomicLong(1);
    private final EventLoopGroup clientTcpGroup = new NioEventLoopGroup(2);

    public ReactiveSipClient(SipNettyServer server,
                             SipResponseRouter responseRouter,
                             SipServerConfiguration configuration) {
        this.server = server;
        this.responseRouter = responseRouter;
        this.configuration = configuration;
    }

    @PreDestroy
    public void shutdown() {
        clientTcpGroup.shutdownGracefully();
    }

    // ==========================================
    // Request Send Methods
    // ==========================================

    /**
     * Sends a SIP request over UDP and returns a Mono emitting the final response (>= 200).
     */
    public Mono<SipResponse> send(SipRequest request, InetSocketAddress destination) {
        return send(request, destination, true);
    }

    public Mono<SipResponse> send(SipRequest request, InetSocketAddress destination, boolean autoPrepareHeaders) {
        return send(request, destination, autoPrepareHeaders, null);
    }

    public Mono<SipResponse> send(SipRequest request, InetSocketAddress destination, Duration timeout) {
        return send(request, destination, true, timeout);
    }

    public Mono<SipResponse> send(SipRequest request, InetSocketAddress destination, boolean autoPrepareHeaders, Duration timeout) {
        return sendWithProvisional(request, destination, autoPrepareHeaders, timeout)
                .filter(SipResponse::isFinal)
                .next();
    }

    /**
     * Sends a SIP request and returns a Flux emitting provisional responses (1xx) and final response.
     * Transport is resolved from Request-URI transport parameter, Via header, or message transport value.
     */
    public Flux<SipResponse> sendWithProvisional(SipRequest request, InetSocketAddress destination) {
        return sendWithProvisional(request, destination, true, null);
    }

    public Flux<SipResponse> sendWithProvisional(SipRequest request, InetSocketAddress destination, Duration timeout) {
        return sendWithProvisional(request, destination, true, timeout);
    }

    public Flux<SipResponse> sendWithProvisional(SipRequest request, InetSocketAddress destination, boolean autoPrepareHeaders) {
        return sendWithProvisional(request, destination, autoPrepareHeaders, null);
    }

    public Flux<SipResponse> sendWithProvisional(SipRequest request,
                                                 InetSocketAddress destination,
                                                 boolean autoPrepareHeaders,
                                                 Duration timeout) {
        SipTransport transport = resolveTransport(request);
        return transport == SipTransport.TCP
                ? sendTcpWithProvisionalInternal(request, destination, autoPrepareHeaders, timeout)
                : sendUdpWithProvisionalInternal(request, destination, autoPrepareHeaders, timeout);
    }

    private Flux<SipResponse> sendUdpWithProvisionalInternal(SipRequest request,
                                                              InetSocketAddress destination,
                                                              boolean autoPrepareHeaders,
                                                              Duration timeout) {
        boolean isInvite = request.getMethod() == SipMethod.INVITE;
        long initialTimeoutMs = (timeout != null)
                ? timeout.toMillis()
                : (isInvite ? configuration.getTimerBDelayMs() : configuration.getTimerFDelayMs());

        return Flux.<SipResponse>create(sink -> {
            AtomicBoolean disposed = new AtomicBoolean(false);
            AtomicReference<String> txKeyRef = new AtomicReference<>();
            AtomicReference<String> methodKeyRef = new AtomicReference<>();

            Runnable unregisterListeners = () -> {
                if (disposed.compareAndSet(false, true)) {
                    String k = txKeyRef.get();
                    if (k != null) responseRouter.unregisterListener(k);
                    String m = methodKeyRef.get();
                    if (m != null) responseRouter.unregisterListener(m);
                }
            };

            try {
                if (autoPrepareHeaders) {
                    prepareHeaders(request, destination, SipTransport.UDP);
                } else {
                    request.setTransport(SipTransport.UDP);
                    if (request.getHeaders().getVia() == null) {
                        int localPort = server.getUdpPort();
                        String branch = "z9hG4bK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                        request.getHeaders().addVia("SIP/2.0/UDP 127.0.0.1:" + localPort + ";branch=" + branch);
                    }
                }

                String branch = extractBranch(request);
                String methodKey = (branch != null) ? (branch + ":" + request.getMethod().name()) : null;
                String transactionKey = branch != null ? branch : request.getCallId();
                txKeyRef.set(transactionKey);
                methodKeyRef.set(methodKey);

                AtomicBoolean anyResponseReceived = new AtomicBoolean(false);
                AtomicBoolean finalResponseReceived = new AtomicBoolean(false);
                AtomicBoolean ringTimeoutStarted = new AtomicBoolean(false);
                AtomicBoolean timerDActive = new AtomicBoolean(false);

                AtomicReference<Disposable> retransmitTimer = new AtomicReference<>();
                AtomicReference<Disposable> timeoutTimer = new AtomicReference<>();

                Consumer<String> scheduleTimeout = (timerName) -> {
                    long delayMs = "Ring timeout".equals(timerName)
                            ? configuration.getRingTimeoutMs()
                            : initialTimeoutMs;
                    Disposable d = Mono.delay(Duration.ofMillis(delayMs), Schedulers.parallel())
                            .subscribe(tick -> {
                                if (!sink.isCancelled() && !finalResponseReceived.get()) {
                                    unregisterListeners.run();
                                    sink.error(new TimeoutException(timerName + " expired after " + delayMs + "ms without response"));
                                }
                            });
                    Disposable old = timeoutTimer.getAndSet(d);
                    if (old != null) old.dispose();
                };

                // Start initial response timeout: Timer B (INVITE) or Timer F (Non-INVITE)
                scheduleTimeout.accept(isInvite ? "Timer B" : "Timer F");

                // Start Timer A (INVITE) or Timer E (Non-INVITE) client retransmissions on UDP
                if (configuration.isClientRetransmitEnabled()) {
                    long t1 = configuration.getT1Ms();
                    long t2 = configuration.getT2Ms();
                    scheduleUdpRetransmissions(request, destination, t1, t2, isInvite,
                            anyResponseReceived, finalResponseReceived, sink, retransmitTimer);
                }

                Consumer<SipResponse> listener = response -> {
                    anyResponseReceived.set(true);
                    Disposable retr = retransmitTimer.getAndSet(null);
                    if (retr != null) retr.dispose();

                    if (finalResponseReceived.get()) {
                        // Absorb retransmitted 3xx-6xx response during Timer D and resend ACK per RFC 3261 §17.1.1.2
                        if (isInvite && response.getStatusCode() >= 300) {
                            LOG.debug("Timer D: Absorbing retransmitted {} response and resending ACK for Call-ID: {}",
                                    response.getStatusCode(), request.getCallId());
                            SipRequest ack = buildAck(request, response, SipTransport.UDP);
                            server.sendUdp(ack, destination);
                        }
                        return;
                    }

                    if (response.isProvisional()) {
                        if (isInvite) {
                            // RFC 3261 §17.1.1.2: Timer B MUST be stopped upon receiving a provisional response
                            Disposable currentTimeout = timeoutTimer.getAndSet(null);
                            if (currentTimeout != null) currentTimeout.dispose();

                            // Transition to Ring Timeout if configured
                            if (configuration.getRingTimeoutMs() > 0 && ringTimeoutStarted.compareAndSet(false, true)) {
                                scheduleTimeout.accept("Ring timeout");
                            }
                        }
                        sink.next(response);
                    } else if (response.isFinal()) {
                        finalResponseReceived.set(true);
                        Disposable currentTimeout = timeoutTimer.getAndSet(null);
                        if (currentTimeout != null) currentTimeout.dispose();

                        // RFC 3261 §17.1.1.2: Send ACK for 3xx-6xx final response to INVITE
                        if (isInvite && response.getStatusCode() >= 300) {
                            timerDActive.set(true);
                            SipRequest ack = buildAck(request, response, SipTransport.UDP);
                            server.sendUdp(ack, destination);

                            // Keep listener active for Timer D (T4 duration) to absorb duplicate error responses
                            long timerDMs = configuration.getT4Ms();
                            Mono.delay(Duration.ofMillis(timerDMs), Schedulers.parallel())
                                    .subscribe(t -> unregisterListeners.run());
                        }

                        sink.next(response);
                        sink.complete();
                    }
                };

                responseRouter.registerListener(transactionKey, listener);
                if (methodKey != null) {
                    responseRouter.registerListener(methodKey, listener);
                }

                sink.onDispose(() -> {
                    Disposable t = timeoutTimer.getAndSet(null);
                    if (t != null) t.dispose();
                    Disposable r = retransmitTimer.getAndSet(null);
                    if (r != null) r.dispose();

                    if (!timerDActive.get()) {
                        unregisterListeners.run();
                    }
                });

                server.sendUdp(request, destination);
            } catch (Exception e) {
                unregisterListeners.run();
                sink.error(e);
            }
        });
    }

    private void scheduleUdpRetransmissions(SipRequest request,
                                            InetSocketAddress destination,
                                            long currentDelay,
                                            long t2,
                                            boolean isInvite,
                                            AtomicBoolean anyResponseReceived,
                                            AtomicBoolean finalResponseReceived,
                                            reactor.core.publisher.FluxSink<SipResponse> sink,
                                            AtomicReference<Disposable> retransmitTimer) {
        Disposable d = Mono.delay(Duration.ofMillis(currentDelay), Schedulers.parallel())
                .subscribe(tick -> {
                    if (anyResponseReceived.get() || finalResponseReceived.get() || sink.isCancelled()) {
                        return;
                    }
                    LOG.debug("Retransmitting SIP {} Call-ID: {} (interval: {}ms)",
                            request.getMethod(), request.getCallId(), currentDelay);
                    server.sendUdp(request, destination);

                    long nextDelay = isInvite ? (currentDelay * 2) : Math.min(currentDelay * 2, t2);
                    scheduleUdpRetransmissions(request, destination, nextDelay, t2, isInvite,
                            anyResponseReceived, finalResponseReceived, sink, retransmitTimer);
                });
        retransmitTimer.set(d);
    }

    private Flux<SipResponse> sendTcpWithProvisionalInternal(SipRequest request,
                                                              InetSocketAddress destination,
                                                              boolean autoPrepareHeaders) {
        return sendTcpWithProvisionalInternal(request, destination, autoPrepareHeaders, null);
    }

    private Flux<SipResponse> sendTcpWithProvisionalInternal(SipRequest request,
                                                              InetSocketAddress destination,
                                                              boolean autoPrepareHeaders,
                                                              Duration timeout) {
        boolean isInvite = request.getMethod() == SipMethod.INVITE;
        long initialTimeoutMs = (timeout != null)
                ? timeout.toMillis()
                : (isInvite ? configuration.getTimerBDelayMs() : configuration.getTimerFDelayMs());

        return Flux.<SipResponse>create(sink -> {
            try {
                if (autoPrepareHeaders) {
                    prepareHeaders(request, destination, SipTransport.TCP);
                } else {
                    request.setTransport(SipTransport.TCP);
                    if (request.getHeaders().getVia() == null) {
                        int localPort = server.getTcpPort();
                        String branch = "z9hG4bK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                        request.getHeaders().addVia("SIP/2.0/TCP 127.0.0.1:" + localPort + ";branch=" + branch);
                    }
                }

                AtomicBoolean finalResponseReceived = new AtomicBoolean(false);
                AtomicBoolean ringTimeoutStarted = new AtomicBoolean(false);
                AtomicReference<Disposable> timeoutTimer = new AtomicReference<>();
                AtomicReference<Channel> channelRef = new AtomicReference<>();

                Consumer<String> scheduleTimeout = (timerName) -> {
                    long delayMs = "Ring timeout".equals(timerName)
                            ? configuration.getRingTimeoutMs()
                            : initialTimeoutMs;
                    Disposable d = Mono.delay(Duration.ofMillis(delayMs), Schedulers.parallel())
                            .subscribe(tick -> {
                                if (!sink.isCancelled() && !finalResponseReceived.get()) {
                                    Channel ch = channelRef.get();
                                    if (ch != null && ch.isOpen()) ch.close();
                                    sink.error(new TimeoutException(timerName + " expired after " + delayMs + "ms without response"));
                                }
                            });
                    Disposable old = timeoutTimer.getAndSet(d);
                    if (old != null) old.dispose();
                };

                scheduleTimeout.accept(isInvite ? "Timer B" : "Timer F");

                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(clientTcpGroup)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.TCP_NODELAY, true)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline().addLast("streamDecoder", new SipStreamFrameDecoder(server.getParser()));
                                ch.pipeline().addLast("streamEncoder", new SipStreamEncoder(server.getEncoder()));
                                ch.pipeline().addLast("clientHandler", new SimpleChannelInboundHandler<SipMessage>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, SipMessage msg) {
                                        if (msg instanceof SipResponse resp) {
                                            if (resp.isProvisional()) {
                                                if (isInvite) {
                                                    // Cancel Timer B upon receipt of provisional response
                                                    Disposable currentTimeout = timeoutTimer.getAndSet(null);
                                                    if (currentTimeout != null) currentTimeout.dispose();

                                                    // Start Ring Timeout if configured
                                                    if (configuration.getRingTimeoutMs() > 0 && ringTimeoutStarted.compareAndSet(false, true)) {
                                                        scheduleTimeout.accept("Ring timeout");
                                                    }
                                                }
                                                sink.next(resp);
                                            } else if (resp.isFinal()) {
                                                finalResponseReceived.set(true);
                                                Disposable currentTimeout = timeoutTimer.getAndSet(null);
                                                if (currentTimeout != null) currentTimeout.dispose();

                                                sink.next(resp);
                                                sink.complete();
                                                ctx.close();
                                            }
                                        }
                                    }

                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                        Disposable currentTimeout = timeoutTimer.getAndSet(null);
                                        if (currentTimeout != null) currentTimeout.dispose();
                                        LOG.error("Client TCP error from {}: {}", destination, cause.getMessage());
                                        sink.error(cause);
                                        ctx.close();
                                    }
                                });
                            }
                        });

                ChannelFuture connectFuture = bootstrap.connect(destination);
                connectFuture.addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        Channel ch = future.channel();
                        channelRef.set(ch);
                        sink.onDispose(() -> {
                            Disposable t = timeoutTimer.getAndSet(null);
                            if (t != null) t.dispose();
                            if (ch.isOpen()) ch.close();
                        });
                        ch.writeAndFlush(request);
                    } else {
                        Disposable t = timeoutTimer.getAndSet(null);
                        if (t != null) t.dispose();
                        sink.error(future.cause());
                    }
                });
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    /**
     * Sends a SIP request one-way (without registering a transaction listener or waiting for response, e.g. ACK).
     */
    public void sendOneWay(SipRequest request, InetSocketAddress destination) {
        SipTransport transport = resolveTransport(request);
        if (transport == SipTransport.TCP) {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(clientTcpGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast("streamEncoder", new SipStreamEncoder(server.getEncoder()));
                        }
                    });
            bootstrap.connect(destination).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    future.channel().writeAndFlush(request).addListener(f -> future.channel().close());
                }
            });
        } else {
            server.sendUdp(request, destination);
        }
    }

    // ==========================================
    // Explicit Transport Overrides (legacy)
    // ==========================================

    public Mono<SipResponse> send(SipRequest request, InetSocketAddress destination, SipTransport transport) {
        request.setTransport(transport);
        return send(request, destination);
    }

    public Flux<SipResponse> sendWithProvisional(SipRequest request, InetSocketAddress destination, SipTransport transport) {
        request.setTransport(transport);
        return sendWithProvisional(request, destination);
    }

    /**
     * Sends an ACK for a 200 OK response (RFC 3261 Section 13.2.2.4).
     */
    public Mono<Void> sendAck(SipRequest originalInvite, SipResponse okResponse, InetSocketAddress destination) {
        return sendAck(originalInvite, okResponse, destination, null, null, resolveTransport(originalInvite));
    }

    /**
     * Sends an ACK with optional body (e.g. SDP answer for late-offer INVITE flows).
     */
    public Mono<Void> sendAck(SipRequest originalInvite,
                              SipResponse okResponse,
                              InetSocketAddress destination,
                              String body,
                              String contentType) {
        return sendAck(originalInvite, okResponse, destination, body, contentType, resolveTransport(originalInvite));
    }

    public Mono<Void> sendAck(SipRequest originalInvite, SipResponse okResponse, InetSocketAddress destination, SipTransport transport) {
        return sendAck(originalInvite, okResponse, destination, null, null, transport);
    }

    public Mono<Void> sendAck(SipRequest originalInvite,
                              SipResponse okResponse,
                              InetSocketAddress destination,
                              String body,
                              String contentType,
                              SipTransport transport) {
        if (transport == SipTransport.TCP) {
            return sendAckTcp(originalInvite, okResponse, destination, body, contentType);
        }

        return Mono.fromRunnable(() -> {
            SipRequest ack = buildAck(originalInvite, okResponse, SipTransport.UDP, body, contentType);
            server.sendUdp(ack, destination);
        });
    }

    public Mono<Void> sendAckTcp(SipRequest originalInvite, SipResponse okResponse, InetSocketAddress destination) {
        return sendAckTcp(originalInvite, okResponse, destination, null, null);
    }

    public Mono<Void> sendAckTcp(SipRequest originalInvite,
                                 SipResponse okResponse,
                                 InetSocketAddress destination,
                                 String body,
                                 String contentType) {
        return Mono.create(sink -> {
            SipRequest ack = buildAck(originalInvite, okResponse, SipTransport.TCP, body, contentType);
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(clientTcpGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast("streamEncoder", new SipStreamEncoder(server.getEncoder()));
                        }
                    });

            bootstrap.connect(destination).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    future.channel().writeAndFlush(ack).addListener(f -> {
                        future.channel().close();
                        sink.success();
                    });
                } else {
                    sink.error(future.cause());
                }
            });
        });
    }

    /**
     * Sends a PRACK request acknowledging receipt of a reliable provisional response (RFC 3262).
     */
    public Mono<SipResponse> sendPrack(SipRequest originalInvite,
                                       SipResponse reliableProvisionalResponse,
                                       InetSocketAddress destination) {
        return sendPrack(originalInvite, reliableProvisionalResponse, destination, resolveTransport(originalInvite));
    }

    public Mono<SipResponse> sendPrack(SipRequest originalInvite,
                                       SipResponse reliableProvisionalResponse,
                                       InetSocketAddress destination,
                                       SipTransport transport) {
        return sendPrack(originalInvite, reliableProvisionalResponse, destination, null, null, transport);
    }

    public Mono<SipResponse> sendPrack(SipRequest originalInvite,
                                       SipResponse reliableProvisionalResponse,
                                       InetSocketAddress destination,
                                       String body,
                                       String contentType,
                                       SipTransport transport) {
        SipRequest prack = buildPrack(originalInvite, reliableProvisionalResponse, transport, body, contentType);
        return send(prack, destination, transport);
    }

    public SipRequest buildPrack(SipRequest originalInvite,
                                 SipResponse reliableProvisionalResponse,
                                 SipTransport transport,
                                 String body,
                                 String contentType) {
        SipUri targetUri = originalInvite.getUri();
        String contact = reliableProvisionalResponse.getContact();
        if (contact != null && !contact.isBlank()) {
            try {
                targetUri = SipUri.parse(contact);
            } catch (Exception ignored) {}
        }

        SipRequest prack = new SipRequest(SipMethod.PRACK, targetUri);
        prack.setTransport(transport);
        SipHeaders prackHeaders = prack.getHeaders();

        prackHeaders.setCallId(reliableProvisionalResponse.getCallId());
        prackHeaders.setFrom(reliableProvisionalResponse.getFrom());
        prackHeaders.setTo(reliableProvisionalResponse.getTo());
        prackHeaders.setCSeq(cseqCounter.incrementAndGet() + " PRACK");
        prackHeaders.setMaxForwards(70);

        String rseq = reliableProvisionalResponse.getHeaders().getRSeq();
        String origCSeq = reliableProvisionalResponse.getCSeq();
        if (rseq != null && origCSeq != null) {
            prackHeaders.setRAck(rseq.trim() + " " + origCSeq.trim());
        }

        if (body != null && !body.isEmpty()) {
            prack.setBody(body);
            if (contentType != null && !contentType.isEmpty()) {
                prackHeaders.setContentType(contentType);
            }
        } else {
            prackHeaders.setContentLength(0);
        }

        return prack;
    }

    /**
     * Sends a BYE request to terminate an active call session.
     */
    public Mono<SipResponse> sendBye(SipRequest originalInvite, SipResponse okResponse, InetSocketAddress destination) {
        return sendBye(originalInvite, okResponse, destination, resolveTransport(originalInvite));
    }

    public Mono<SipResponse> sendBye(SipRequest originalInvite, SipResponse okResponse, InetSocketAddress destination, SipTransport transport) {
        SipRequest bye = new SipRequest(SipMethod.BYE, originalInvite.getUri());
        SipHeaders byeHeaders = bye.getHeaders();

        byeHeaders.setCallId(okResponse.getCallId());
        byeHeaders.setFrom(okResponse.getFrom());
        byeHeaders.setTo(okResponse.getTo());
        byeHeaders.setCSeq(cseqCounter.incrementAndGet() + " BYE");
        byeHeaders.setMaxForwards(70);

        return send(bye, destination, transport);
    }

    /**
     * Sends a CANCEL request to terminate a pending INVITE transaction per RFC 3261 §9.
     */
    public Mono<SipResponse> sendCancel(SipRequest originalInvite, InetSocketAddress destination) {
        return sendCancel(originalInvite, destination, resolveTransport(originalInvite));
    }

    public Mono<SipResponse> sendCancel(SipRequest originalInvite, InetSocketAddress destination, SipTransport transport) {
        SipRequest cancel = new SipRequest(SipMethod.CANCEL, originalInvite.getUri());
        cancel.setTransport(transport);
        SipHeaders cancelHeaders = cancel.getHeaders();

        // RFC 3261 §9.1: Call-ID, To, From, CSeq number, and topmost Via MUST match the request being cancelled
        cancelHeaders.setCallId(originalInvite.getCallId());
        cancelHeaders.setFrom(originalInvite.getFrom());
        cancelHeaders.setTo(originalInvite.getTo());
        long seq = originalInvite.getCSeqNumber() > 0 ? originalInvite.getCSeqNumber() : 1;
        cancelHeaders.setCSeq(seq + " CANCEL");
        cancelHeaders.setMaxForwards(70);

        String topVia = originalInvite.getHeaders().getVia();
        if (topVia != null) {
            cancelHeaders.addVia(topVia);
        }

        return send(cancel, destination, transport);
    }

    /**
     * Sends a mid-dialog DTMF tone using SIP INFO (RFC 2976 / RFC 6086) with application/dtmf-relay.
     */
    public Mono<SipResponse> sendDtmf(SipRequest originalInvite, SipResponse okResponse, DtmfSignal signal, InetSocketAddress destination) {
        return sendDtmf(originalInvite, okResponse, signal, destination, resolveTransport(originalInvite));
    }

    public Mono<SipResponse> sendDtmf(SipRequest originalInvite, SipResponse okResponse, DtmfSignal signal, InetSocketAddress destination, SipTransport transport) {
        SipRequest info = new SipRequest(SipMethod.INFO, originalInvite.getUri());
        info.setTransport(transport);
        SipHeaders headers = info.getHeaders();
        headers.setCallId(okResponse.getCallId());
        headers.setFrom(okResponse.getFrom());
        headers.setTo(okResponse.getTo());
        headers.setCSeq(cseqCounter.incrementAndGet() + " INFO");
        headers.setMaxForwards(70);
        info.setDtmf(signal);
        return send(info, destination, transport);
    }

    /**
     * Sends a DTMF signal using SIP MESSAGE (RFC 3428).
     */
    public Mono<SipResponse> sendDtmfMessage(String targetUri, DtmfSignal signal, InetSocketAddress destination) {
        return sendDtmfMessage(targetUri, signal, destination, SipTransport.UDP);
    }

    public Mono<SipResponse> sendDtmfMessage(String targetUri, DtmfSignal signal, InetSocketAddress destination, SipTransport transport) {
        int localPort = (transport == SipTransport.TCP) ? server.getTcpPort() : server.getUdpPort();
        SipRequest msg = SipRequest.builder(SipMethod.MESSAGE, targetUri)
                .from("<sip:client@127.0.0.1:" + localPort + ">;tag=" + UUID.randomUUID().toString().substring(0, 8))
                .to("<" + targetUri + ">")
                .callId("dtmf-msg-" + UUID.randomUUID())
                .dtmf(signal)
                .build();
        msg.setTransport(transport);
        return send(msg, destination);
    }

    /**
     * Sends a general SIP INFO request (RFC 2976 / RFC 6086).
     */
    public Mono<SipResponse> sendInfo(SipRequest request, InetSocketAddress destination) {
        return send(request, destination);
    }

    private SipRequest buildAck(SipRequest originalInvite, SipResponse okResponse, SipTransport transport) {
        return buildAck(originalInvite, okResponse, transport, null, null);
    }

    private SipRequest buildAck(SipRequest originalInvite,
                                SipResponse okResponse,
                                SipTransport transport,
                                String body,
                                String contentType) {
        SipRequest ack = new SipRequest(SipMethod.ACK, originalInvite.getUri());
        ack.setTransport(transport);
        SipHeaders ackHeaders = ack.getHeaders();

        ackHeaders.setCallId(okResponse.getCallId());
        ackHeaders.setFrom(okResponse.getFrom());
        ackHeaders.setTo(okResponse.getTo());

        long seq = 1;
        if (originalInvite.getCSeq() != null) {
            String[] parts = originalInvite.getCSeq().trim().split("\\s+");
            try {
                seq = Long.parseLong(parts[0]);
            } catch (NumberFormatException ignored) {}
        }
        ackHeaders.setCSeq(seq + " ACK");
        ackHeaders.setMaxForwards(70);

        int localPort = (transport == SipTransport.TCP) ? server.getTcpPort() : server.getUdpPort();
        String newBranch = "z9hG4bK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ackHeaders.addVia(transport.getViaProtocol() + " 127.0.0.1:" + localPort + ";branch=" + newBranch);

        if (body != null && !body.isEmpty()) {
            ack.setBody(body);
            if (contentType != null && !contentType.isEmpty()) {
                ackHeaders.setContentType(contentType);
            }
        } else {
            ackHeaders.setContentLength(0);
        }

        return ack;
    }

    private void prepareHeaders(SipRequest request, InetSocketAddress destination, SipTransport transport) {
        request.setTransport(transport);
        SipHeaders headers = request.getHeaders();
        int localPort = (transport == SipTransport.TCP) ? server.getTcpPort() : server.getUdpPort();
        String hostPort = "127.0.0.1:" + localPort;

        if (request.getCallId() == null) {
            headers.setCallId(UUID.randomUUID().toString() + "@" + hostPort);
        }

        if (request.getFrom() == null) {
            headers.setFrom("<sip:client@" + hostPort + ">;tag=" + UUID.randomUUID().toString().substring(0, 8));
        }

        if (request.getTo() == null) {
            headers.setTo("<" + request.getUri().toString() + ">");
        }

        if (request.getCSeq() == null) {
            headers.setCSeq(cseqCounter.getAndIncrement() + " " + request.getMethod().name());
        }

        if (headers.getVia() == null) {
            String branch = "z9hG4bK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            headers.addVia(transport.getViaProtocol() + " " + hostPort + ";branch=" + branch);
        }

        if (!headers.contains(SipHeaders.MAX_FORWARDS)) {
            headers.setMaxForwards(70);
        }

        if (!headers.contains(SipHeaders.CONTENT_LENGTH)) {
            headers.setContentLength(request.getBody() != null ? request.getBody().length : 0);
        }
    }

    private String extractBranch(SipRequest request) {
        String via = request.getVia();
        if (via != null) {
            int idx = via.indexOf("branch=");
            if (idx != -1) {
                int end = via.indexOf(';', idx);
                return (end != -1) ? via.substring(idx + 7, end).trim() : via.substring(idx + 7).trim();
            }
        }
        return null;
    }

    private SipTransport resolveTransport(SipRequest request) {
        if (request == null) {
            return SipTransport.UDP;
        }

        SipUri uri = request.getUri();
        if (uri != null) {
            SipTransport fromUri = parseTransportToken(uri.getParameter("transport"));
            if (fromUri != null) {
                return fromUri;
            }
        }

        String via = request.getVia();
        if (via != null && !via.isBlank()) {
            return SipTransport.fromVia(via);
        }

        return request.getTransport() != null ? request.getTransport() : SipTransport.UDP;
    }

    private SipTransport parseTransportToken(String transportToken) {
        if (transportToken == null || transportToken.isBlank()) {
            return null;
        }
        return switch (transportToken.trim().toUpperCase()) {
            case "UDP" -> SipTransport.UDP;
            case "TCP" -> SipTransport.TCP;
            case "TLS" -> SipTransport.TLS;
            case "WS" -> SipTransport.WS;
            case "WSS" -> SipTransport.WSS;
            default -> null;
        };
    }
}
