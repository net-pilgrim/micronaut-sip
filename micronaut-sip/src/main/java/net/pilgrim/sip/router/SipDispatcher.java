package net.pilgrim.sip.router;

import io.micronaut.context.BeanContext;
import io.micronaut.context.processor.ExecutableMethodProcessor;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.pilgrim.sip.annotation.*;
import net.pilgrim.sip.config.SipServerConfiguration;
import net.pilgrim.sip.metrics.SipMetrics;
import net.pilgrim.sip.model.*;
import net.pilgrim.sip.filter.SipFilterChain;
import net.pilgrim.sip.filter.SipServerFilter;
import net.pilgrim.sip.session.SipSession;
import net.pilgrim.sip.session.SipSessionManager;
import net.pilgrim.sip.transport.SipMessageSender;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Dispatches incoming SIP requests to annotated @SipController handlers reactively using ExecutableMethod.
 * Performs RFC 3261 request validation (mandatory headers, Require extensions, duplicate route prevention),
 * custom @SipError handling, RFC 3261 §17.2.1 auto 100 Trying generation, RFC 3261 §9.2 CANCEL transaction
 * matching with auto 487 Request Terminated emission, Micrometer metrics instrumentation, and reactive
 * server filter chain execution (@SipFilter / SipServerFilter).
 */
@Singleton
public class SipDispatcher implements ExecutableMethodProcessor<SipController> {

    private static final Logger LOG = LoggerFactory.getLogger(SipDispatcher.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("replaces", "100rel");

    private final BeanContext beanContext;
    private final SipSessionManager sessionManager;
    private final SipServerConfiguration configuration;
    private final SipMetrics metrics;
    private final List<SipRoute> routes = new CopyOnWriteArrayList<>();
    private final List<SipErrorRoute> errorRoutes = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, PendingServerTransaction> pendingTransactions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Pending2xxRetransmission> pending2xxRetransmissions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SipResponse> nonInviteResponseCache = new ConcurrentHashMap<>();
    private final List<SipServerFilter> filters = new CopyOnWriteArrayList<>();

    private volatile Scheduler timerScheduler = Schedulers.parallel();

    public static class Pending2xxRetransmission {
        private final String callId;
        private final SipRequest originalRequest;
        private final SipResponse response;
        private final Consumer<SipResponse> sender;
        private final AtomicBoolean ackReceived = new AtomicBoolean(false);
        private final AtomicReference<Disposable> retransmitDisposable = new AtomicReference<>();
        private final AtomicReference<Disposable> timerHDisposable = new AtomicReference<>();

        public Pending2xxRetransmission(String callId, SipRequest originalRequest, SipResponse response, Consumer<SipResponse> sender) {
            this.callId = callId;
            this.originalRequest = originalRequest;
            this.response = response;
            this.sender = sender;
        }

        public Pending2xxRetransmission(String callId, SipResponse response, Consumer<SipResponse> sender) {
            this(callId, null, response, sender);
        }

        public String getCallId() {
            return callId;
        }

        public SipRequest getOriginalRequest() {
            return originalRequest;
        }

        public SipResponse getResponse() {
            return response;
        }

        public Consumer<SipResponse> getSender() {
            return sender;
        }

        public AtomicBoolean getAckReceived() {
            return ackReceived;
        }

        public AtomicReference<Disposable> getRetransmitDisposable() {
            return retransmitDisposable;
        }

        public void setRetransmitDisposable(Disposable d) {
            this.retransmitDisposable.set(d);
        }

        public void setTimerHDisposable(Disposable d) {
            this.timerHDisposable.set(d);
        }

        public void cancel() {
            ackReceived.set(true);
            Disposable d1 = retransmitDisposable.getAndSet(null);
            if (d1 != null) d1.dispose();
            Disposable d2 = timerHDisposable.getAndSet(null);
            if (d2 != null) d2.dispose();
        }
    }

    public Scheduler getTimerScheduler() {
        return timerScheduler;
    }

    public void setTimerScheduler(Scheduler timerScheduler) {
        this.timerScheduler = timerScheduler != null ? timerScheduler : Schedulers.parallel();
    }

    public SipDispatcher(BeanContext beanContext, SipSessionManager sessionManager) {
        this(beanContext, sessionManager, new SipServerConfiguration(), null, null);
    }

    public SipDispatcher(BeanContext beanContext, SipSessionManager sessionManager, SipServerConfiguration configuration) {
        this(beanContext, sessionManager, configuration, null, null);
    }

    public SipDispatcher(BeanContext beanContext, SipSessionManager sessionManager, SipServerConfiguration configuration, @Nullable SipMetrics metrics) {
        this(beanContext, sessionManager, configuration, metrics, null);
    }

    @Inject
    public SipDispatcher(BeanContext beanContext,
                         SipSessionManager sessionManager,
                         SipServerConfiguration configuration,
                         @Nullable SipMetrics metrics,
                         @Nullable List<SipServerFilter> filters) {
        this.beanContext = beanContext;
        this.sessionManager = sessionManager;
        this.configuration = configuration != null ? configuration : new SipServerConfiguration();
        this.metrics = metrics != null ? metrics : SipMetrics.NOOP;
        if (filters != null) {
            this.filters.addAll(filters);
            sortFilters();
        }
    }

    public void addFilter(SipServerFilter filter) {
        if (filter != null) {
            filters.add(filter);
            sortFilters();
        }
    }

    public void removeFilter(SipServerFilter filter) {
        if (filter != null) {
            filters.remove(filter);
        }
    }

    private void sortFilters() {
        filters.sort(Comparator.comparingInt(SipServerFilter::getOrder));
    }

    public List<SipServerFilter> getFilters() {
        return Collections.unmodifiableList(filters);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <B> void process(BeanDefinition<B> beanDefinition, ExecutableMethod<B, ?> method) {
        if (method.hasAnnotation(SipError.class)) {
            AnnotationValue<SipError> errorAnn = method.getAnnotation(SipError.class);
            Class<? extends Throwable> exType = errorAnn != null
                    ? (Class<? extends Throwable>) errorAnn.classValue().orElse(Throwable.class)
                    : Throwable.class;
            int status = errorAnn != null ? errorAnn.intValue("status").orElse(-1) : -1;
            B controllerInstance = beanContext.getBean(beanDefinition);
            SipErrorRoute errorRoute = new SipErrorRoute(exType, status, controllerInstance, (ExecutableMethod<Object, ?>) method);
            errorRoutes.add(errorRoute);
            LOG.info("Registered SIP error handler for {}: {}.{}()", exType.getSimpleName(),
                    beanDefinition.getBeanType().getSimpleName(), method.getMethodName());
            return;
        }

        String prefix = beanDefinition.stringValue(SipController.class).orElse("");

        SipMethod sipMethod = null;
        String customMethod = null;
        String path = "";

        AnnotationValue<OnSipMethod> ann = method.findAnnotation(OnSipMethod.class).orElse(null);
        if (ann != null) {
            sipMethod = ann.enumValue(SipMethod.class).orElse(SipMethod.INVITE);
            customMethod = ann.stringValue("custom").filter(s -> !s.isEmpty()).orElse(null);
            path = ann.stringValue("path").orElse("");
        }

        // Direct check or fallback if path was not mapped via AliasFor or OnSipMethod directly
        if (path.isEmpty()) {
            if (method.hasAnnotation(OnInvite.class)) {
                sipMethod = SipMethod.INVITE;
                path = method.stringValue(OnInvite.class).orElse("");
            } else if (method.hasAnnotation(OnBye.class)) {
                sipMethod = SipMethod.BYE;
                path = method.stringValue(OnBye.class).orElse("");
            } else if (method.hasAnnotation(OnAck.class)) {
                sipMethod = SipMethod.ACK;
                path = method.stringValue(OnAck.class).orElse("");
            } else if (method.hasAnnotation(OnCancel.class)) {
                sipMethod = SipMethod.CANCEL;
                path = method.stringValue(OnCancel.class).orElse("");
            } else if (method.hasAnnotation(OnRegister.class)) {
                sipMethod = SipMethod.REGISTER;
                path = method.stringValue(OnRegister.class).orElse("");
            } else if (method.hasAnnotation(OnOptions.class)) {
                sipMethod = SipMethod.OPTIONS;
                path = method.stringValue(OnOptions.class).orElse("");
            } else if (method.hasAnnotation(OnMessage.class)) {
                sipMethod = SipMethod.MESSAGE;
                path = method.stringValue(OnMessage.class).orElse("");
            } else if (method.hasAnnotation(OnInfo.class)) {
                sipMethod = SipMethod.INFO;
                path = method.stringValue(OnInfo.class).orElse("");
            }
        }

        if (sipMethod == null && customMethod == null) {
            // Not a SIP handler method
            return;
        }

        String combinedPattern = combinePattern(prefix, path);
        B controllerInstance = beanContext.getBean(beanDefinition);

        SipRoute route = new SipRoute(
                sipMethod,
                customMethod,
                combinedPattern,
                controllerInstance,
                (ExecutableMethod<Object, ?>) method
        );

        addRoute(route);
        LOG.info("Registered SIP route: {} '{}' -> {}.{}()",
                customMethod != null ? customMethod : sipMethod,
                combinedPattern,
                beanDefinition.getBeanType().getSimpleName(),
                method.getMethodName());
    }

    public void registerController(Object controller) {
        Class<?> clazz = controller.getClass();
        try {
            BeanDefinition<?> beanDef = beanContext.getBeanDefinition(clazz);
            for (ExecutableMethod<?, ?> method : beanDef.getExecutableMethods()) {
                process((BeanDefinition<Object>) beanDef, (ExecutableMethod<Object, ?>) method);
            }
            return;
        } catch (Exception e) {
            LOG.debug("Could not resolve BeanDefinition for {}, falling back to reflection: {}", clazz.getName(), e.getMessage());
        }

        // Reflection fallback for standalone objects not managed by BeanContext
        SipController controllerAnn = clazz.getAnnotation(SipController.class);
        String prefix = controllerAnn != null ? controllerAnn.value() : "";

        for (Method method : clazz.getMethods()) {
            if (method.isAnnotationPresent(SipError.class)) {
                SipError errorAnn = method.getAnnotation(SipError.class);
                Class<? extends Throwable> exType = errorAnn.value();
                int status = errorAnn.status();
                SipErrorRoute errorRoute = new SipErrorRoute(exType, status, controller, method);
                errorRoutes.add(errorRoute);
                LOG.info("Registered fallback SIP error handler for {}: {}.{}()", exType.getSimpleName(),
                        clazz.getSimpleName(), method.getName());
            } else if (method.isAnnotationPresent(OnInvite.class)) {
                OnInvite ann = method.getAnnotation(OnInvite.class);
                String pattern = combinePattern(prefix, ann.value());
                addRoute(new SipRoute(SipMethod.INVITE, null, pattern, controller, method));
            } else if (method.isAnnotationPresent(OnBye.class)) {
                OnBye ann = method.getAnnotation(OnBye.class);
                String pattern = combinePattern(prefix, ann.value());
                addRoute(new SipRoute(SipMethod.BYE, null, pattern, controller, method));
            } else if (method.isAnnotationPresent(OnAck.class)) {
                OnAck ann = method.getAnnotation(OnAck.class);
                String pattern = combinePattern(prefix, ann.value());
                addRoute(new SipRoute(SipMethod.ACK, null, pattern, controller, method));
            } else if (method.isAnnotationPresent(OnCancel.class)) {
                OnCancel ann = method.getAnnotation(OnCancel.class);
                String pattern = combinePattern(prefix, ann.value());
                addRoute(new SipRoute(SipMethod.CANCEL, null, pattern, controller, method));
            } else if (method.isAnnotationPresent(OnRegister.class)) {
                OnRegister ann = method.getAnnotation(OnRegister.class);
                String pattern = combinePattern(prefix, ann.value());
                addRoute(new SipRoute(SipMethod.REGISTER, null, pattern, controller, method));
            } else if (method.isAnnotationPresent(OnOptions.class)) {
                OnOptions ann = method.getAnnotation(OnOptions.class);
                String pattern = combinePattern(prefix, ann.value());
                addRoute(new SipRoute(SipMethod.OPTIONS, null, pattern, controller, method));
            } else if (method.isAnnotationPresent(OnMessage.class)) {
                OnMessage ann = method.getAnnotation(OnMessage.class);
                String pattern = combinePattern(prefix, ann.value());
                addRoute(new SipRoute(SipMethod.MESSAGE, null, pattern, controller, method));
            } else if (method.isAnnotationPresent(OnInfo.class)) {
                OnInfo ann = method.getAnnotation(OnInfo.class);
                String pattern = combinePattern(prefix, ann.value());
                addRoute(new SipRoute(SipMethod.INFO, null, pattern, controller, method));
            } else if (method.isAnnotationPresent(OnSipMethod.class)) {
                OnSipMethod ann = method.getAnnotation(OnSipMethod.class);
                String pattern = combinePattern(prefix, ann.path());
                addRoute(new SipRoute(ann.value(), ann.custom(), pattern, controller, method));
            }
        }
    }

    private synchronized void addRoute(SipRoute newRoute) {
        for (SipRoute existing : routes) {
            if (existing.getMethod() == newRoute.getMethod()
                    && Objects.equals(existing.getCustomMethod(), newRoute.getCustomMethod())
                    && Objects.equals(existing.getUriPattern(), newRoute.getUriPattern())) {
                String desc = (newRoute.getCustomMethod() != null ? newRoute.getCustomMethod() : newRoute.getMethod())
                        + " '" + newRoute.getUriPattern() + "'";
                throw new IllegalStateException("Duplicate SIP route detected for " + desc + ": already handled by "
                        + existing.getControllerInstance().getClass().getName());
            }
        }
        routes.add(newRoute);
        routes.sort((r1, r2) -> Integer.compare(r2.getUriPattern().length(), r1.getUriPattern().length()));
    }

    private String combinePattern(String prefix, String value) {
        if (prefix == null || prefix.isEmpty()) return value != null ? value : "";
        if (value == null || value.isEmpty()) return prefix;
        return prefix + "/" + value;
    }

    public String resolveTransactionKey(SipRequest request) {
        String branch = request.getBranch();
        if (branch != null && !branch.trim().isEmpty()) {
            return branch.trim();
        }
        return request.getCallId() + ":" + request.getCSeqNumber();
    }

    public String resolveServerTransactionKey(SipRequest request) {
        String branch = request.getBranch();
        String method = request.getMethod() != null ? request.getMethod().name() : request.getMethodName();
        if (branch != null && !branch.trim().isEmpty()) {
            return branch.trim() + ":" + method;
        }
        return request.getCallId() + ":" + request.getCSeqNumber() + ":" + method;
    }

    /**
     * Dispatches a SIP request to the matching controller method.
     * Validates mandatory RFC 3261 headers and Require extensions,
     * matches CANCEL transactions with auto 487 emission,
     * tracks INVITE transactions, records Micrometer metrics,
     * and executes the reactive SIP filter chain (@SipFilter / SipServerFilter).
     */
    public void dispatch(SipRequest request, Consumer<SipResponse> responseSender) {
        LOG.debug("Dispatching SIP request: {} {} (Call-ID: {})", request.getMethod(), request.getUri(), request.getCallId());

        long startNanos = System.nanoTime();
        String transportName = request.getTransport() != null ? request.getTransport().name().toLowerCase(Locale.ROOT) : "udp";
        String methodName = request.getMethod() != null ? request.getMethod().name() : "UNKNOWN";
        metrics.requestReceived(methodName, transportName);

        final Consumer<SipResponse> instrumentedSender;
        if (responseSender instanceof SipMessageSender msgSender) {
            instrumentedSender = new SipMessageSender() {
                @Override
                public void sendMessage(SipMessage message) {
                    if (message instanceof SipResponse resp) {
                        metrics.responseSent(methodName, resp.getStatusCode(), transportName);
                        metrics.requestDuration(methodName, resp.getStatusCode(), Duration.ofNanos(System.nanoTime() - startNanos));
                    }
                    msgSender.sendMessage(message);
                }
            };
        } else {
            instrumentedSender = resp -> {
                metrics.responseSent(methodName, resp.getStatusCode(), transportName);
                metrics.requestDuration(methodName, resp.getStatusCode(), Duration.ofNanos(System.nanoTime() - startNanos));
                responseSender.accept(resp);
            };
        }

        // RFC 3261 §17.2.2 / Timer J: Check server transaction cache for retransmitted non-INVITE requests
        if (request.getMethod() != SipMethod.INVITE && request.getMethod() != SipMethod.ACK) {
            String txKey = resolveServerTransactionKey(request);
            SipResponse cachedResp = nonInviteResponseCache.get(txKey);
            if (cachedResp != null) {
                LOG.debug("Timer J: Replaying cached response for duplicate {} (Key: {})", request.getMethod(), txKey);
                instrumentedSender.accept(cachedResp);
                return;
            }
        }

        List<SipServerFilter> activeFilters = new ArrayList<>();
        for (SipServerFilter filter : filters) {
            if (matchesFilter(filter, request)) {
                activeFilters.add(filter);
            }
        }

        SipFilterChain terminal = req -> executeTerminal(req, instrumentedSender);
        Publisher<SipResponse> chainPublisher = buildChain(activeFilters, 0, terminal).proceed(request);

        Flux.from(chainPublisher).subscribe(
                instrumentedSender::accept,
                error -> {
                    LOG.error("Unhandled error processing SIP request: {} {}", request.getMethod(), request.getUri(), error);
                    if (request.getMethod() != SipMethod.ACK) {
                        instrumentedSender.accept(SipResponse.serverError(request, error.getMessage()));
                    }
                }
        );
    }

    private boolean matchesFilter(SipServerFilter filter, SipRequest request) {
        if (!filter.matches(request)) {
            return false;
        }
        net.pilgrim.sip.annotation.SipFilter ann = filter.getClass().getAnnotation(net.pilgrim.sip.annotation.SipFilter.class);
        if (ann != null) {
            if (ann.methods().length > 0) {
                boolean methodMatch = false;
                for (SipMethod m : ann.methods()) {
                    if (m == request.getMethod()) {
                        methodMatch = true;
                        break;
                    }
                }
                if (!methodMatch) return false;
            }
            if (ann.customMethods().length > 0) {
                boolean customMatch = false;
                String reqMethod = request.getMethodName();
                for (String cm : ann.customMethods()) {
                    if (cm.equalsIgnoreCase(reqMethod)) {
                        customMatch = true;
                        break;
                    }
                }
                if (!customMatch) return false;
            }
            if (ann.patterns().length > 0) {
                boolean patternMatch = false;
                String uriStr = request.getUri() != null ? request.getUri().toString() : "";
                for (String p : ann.patterns()) {
                    if (patternMatches(p, uriStr)) {
                        patternMatch = true;
                        break;
                    }
                }
                if (!patternMatch) return false;
            }
        }
        return true;
    }

    private boolean patternMatches(String pattern, String uri) {
        if (pattern == null || pattern.isEmpty() || "/**".equals(pattern) || "*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return uri.startsWith(prefix) || uri.contains(prefix);
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return uri.startsWith(prefix) || uri.contains(prefix);
        }
        return uri.equals(pattern) || uri.contains(pattern);
    }

    private SipFilterChain buildChain(List<SipServerFilter> filterList, int index, SipFilterChain terminal) {
        if (index >= filterList.size()) {
            return terminal;
        }
        SipServerFilter filter = filterList.get(index);
        return req -> filter.doFilter(req, buildChain(filterList, index + 1, terminal));
    }

    private Publisher<SipResponse> executeTerminal(SipRequest request, Consumer<SipResponse> originalSender) {
        return Flux.create(sink -> {
            // RFC 3261 Section 8.2.1: Validate mandatory headers (To, From, Call-ID, CSeq, Via)
            if (request.getTo() == null || request.getTo().trim().isEmpty()
                    || request.getFrom() == null || request.getFrom().trim().isEmpty()
                    || request.getCallId() == null || request.getCallId().trim().isEmpty()
                    || request.getCSeq() == null || request.getCSeq().trim().isEmpty()
                    || request.getHeaders().get(SipHeaders.VIA) == null) {
                LOG.warn("Rejecting malformed SIP request (missing mandatory headers): {} (Call-ID: {})",
                        request.getMethod(), request.getCallId());
                metrics.requestRejected("missing_mandatory_headers");
                if (request.getMethod() != SipMethod.ACK) {
                    sink.next(SipResponse.badRequest(request, "Missing mandatory RFC 3261 header (To, From, Call-ID, CSeq, Via)"));
                }
                sink.complete();
                return;
            }

            // RFC 3261 Section 8.2.2 / 21.4.18: Validate Require header
            if (request.getHeaders().contains(SipHeaders.REQUIRE)) {
                String requireVal = request.getHeaders().get(SipHeaders.REQUIRE);
                List<String> unsupported = getUnsupportedExtensions(requireVal);
                if (!unsupported.isEmpty()) {
                    String unsupportedStr = String.join(", ", unsupported);
                    LOG.info("Rejecting SIP request with unsupported Require option(s): {}", unsupportedStr);
                    metrics.requestRejected("unsupported_extension");
                    if (request.getMethod() != SipMethod.ACK) {
                        sink.next(SipResponse.badExtension(request, unsupportedStr));
                    }
                    sink.complete();
                    return;
                }
            }

            // RFC 3261 §13.3.1.4 / Timer G & H: Stop 2xx retransmissions and ACK wait timer upon receiving matching valid ACK
            if (request.getMethod() == SipMethod.ACK) {
                Pending2xxRetransmission pending = pending2xxRetransmissions.remove(request.getCallId());
                if (pending != null) {
                    LOG.debug("ACK received for 2xx INVITE Call-ID: {}. Cancelling Timer G and Timer H.", request.getCallId());
                    pending.cancel();
                }
            }

            // RFC 3261 Section 9.2: CANCEL Transaction Matching and auto 487 Request Terminated
            if (request.getMethod() == SipMethod.CANCEL && configuration.isAutoCancelEnabled()) {
                String txKey = resolveTransactionKey(request);
                PendingServerTransaction pendingTx = pendingTransactions.remove(txKey);
                if (pendingTx == null) {
                    // Fallback: search by Call-ID and CSeq sequence number
                    for (Map.Entry<String, PendingServerTransaction> entry : pendingTransactions.entrySet()) {
                        PendingServerTransaction candidate = entry.getValue();
                        if (Objects.equals(candidate.getRequest().getCallId(), request.getCallId())
                                && candidate.getRequest().getCSeqNumber() == request.getCSeqNumber()) {
                            pendingTx = pendingTransactions.remove(entry.getKey());
                            break;
                        }
                    }
                }

                if (pendingTx == null || pendingTx.isFinalResponseSent()) {
                    LOG.warn("Received CANCEL for non-existent or completed transaction: Call-ID={}, CSeq={}",
                            request.getCallId(), request.getCSeq());
                    metrics.requestRejected("transaction_does_not_exist");
                    sink.next(SipResponse.transactionDoesNotExist(request));
                    sink.complete();
                    return;
                }

                LOG.info("Matching pending INVITE transaction found for CANCEL: Call-ID={}. Cancelling transaction.", request.getCallId());
                boolean cancelled = pendingTx.cancel();
                if (cancelled) {
                    // 1. Emit 200 OK to the CANCEL request
                    sink.next(SipResponse.ok(request));
                    sink.complete();

                    // 2. Emit 487 Request Terminated to the pending INVITE
                    pendingTx.getOriginalSender().accept(SipResponse.requestTerminated(pendingTx.getRequest()));

                    // 3. Transition session state to TERMINATED if exists
                    SipSession session = sessionManager.getSession(request.getCallId());
                    if (session != null) {
                        session.setState(SipSession.State.TERMINATED);
                    }

                    // 4. If controller has an @OnCancel handler, invoke it for application-level cleanup
                    for (SipRoute route : routes) {
                        if (route.matches(request)) {
                            try {
                                route.invoke(request, sessionManager);
                            } catch (Throwable t) {
                                LOG.error("Error executing @OnCancel route: {}", t.getMessage(), t);
                            }
                            break;
                        }
                    }
                    return;
                }
            }

            // Track pending INVITE transaction
            PendingServerTransaction pendingTx = null;
            if (request.getMethod() == SipMethod.INVITE) {
                String txKey = resolveTransactionKey(request);
                PendingServerTransaction existingTx = pendingTransactions.get(txKey);

                // Case 1: Retransmission of the exact same INVITE transaction (same branch)
                if (existingTx != null) {
                    if (existingTx.getLastProvisionalResponse() != null) {
                        LOG.debug("Retransmitting last provisional response {} for duplicate INVITE (branch: {})",
                                existingTx.getLastProvisionalResponse().getStatusCode(), txKey);
                        originalSender.accept(existingTx.getLastProvisionalResponse());
                    }
                    sink.complete();
                    return;
                }

                // Case 2: RFC 3261 §14.2: A second INVITE arrives on the same Call-ID before the first
                // transaction has finished processing (still in Proceeding state, no final response yet).
                // UAS MUST return 500 Server Internal Error with Retry-After: 0..10 (or 491 Request Pending).
                PendingServerTransaction proceedingTx = null;
                for (PendingServerTransaction candidate : pendingTransactions.values()) {
                    if (Objects.equals(candidate.getRequest().getCallId(), request.getCallId())
                            && !candidate.isFinalResponseSent() && !candidate.isCancelled()) {
                        proceedingTx = candidate;
                        break;
                    }
                }
                if (proceedingTx != null) {
                    LOG.warn("Second INVITE received while first INVITE is still pending for Call-ID: {}. Returning 500 with Retry-After per RFC 3261 §14.2", request.getCallId());
                    SipResponse err = SipResponse.serverError(request, "Server Internal Error");
                    int retrySeconds = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 11);
                    err.getHeaders().set(SipHeaders.RETRY_AFTER, String.valueOf(retrySeconds));
                    sink.next(err);
                    sink.complete();
                    return;
                }

                pendingTx = new PendingServerTransaction(txKey, request, originalSender);
                pendingTransactions.put(txKey, pendingTx);
            }
            final PendingServerTransaction activeTx = pendingTx;

            // Auto 100 Trying timer for INVITE if enabled (RFC 3261 §17.2.1)
            final Disposable tryingTimer;
            AtomicBoolean responseEmitted = new AtomicBoolean(false);
            if (request.getMethod() == SipMethod.INVITE && configuration.isAuto100TryingEnabled()) {
                tryingTimer = Mono.delay(Duration.ofMillis(configuration.getAuto100TryingDelayMs()))
                        .subscribe(tick -> {
                            if (!responseEmitted.get() && (activeTx == null || !activeTx.isCancelled())) {
                                LOG.debug("Auto-emitting 100 Trying for INVITE Call-ID: {}", request.getCallId());
                                sink.next(SipResponse.trying(request));
                            }
                        });

                if (activeTx != null) {
                    activeTx.addDisposable(tryingTimer);
                }
            } else {
                tryingTimer = null;
            }

            sink.onCancel(() -> {
                if (tryingTimer != null) tryingTimer.dispose();
                if (activeTx != null) {
                    pendingTransactions.remove(activeTx.getTransactionKey());
                    activeTx.disposeAll();
                }
            });

            Consumer<SipResponse> terminalSender = resp -> {
                responseEmitted.set(true);
                if (tryingTimer != null) tryingTimer.dispose();
                if (activeTx != null && activeTx.isCancelled()) {
                    LOG.debug("Suppressing response {} for cancelled transaction Call-ID: {}", resp.getStatusCode(), request.getCallId());
                    return;
                }
                if (resp.isFinal()) {
                    // Timer J: Cache final response for non-INVITE server transactions over UDP (RFC 3261 §17.2.2)
                    if (request.getMethod() != SipMethod.INVITE && request.getMethod() != SipMethod.ACK
                            && request.getTransport() != SipTransport.TCP && configuration.getTimerJDelayMs() > 0) {
                        String txKey = resolveServerTransactionKey(request);
                        nonInviteResponseCache.put(txKey, resp);
                        Mono.delay(Duration.ofMillis(configuration.getTimerJDelayMs()), Schedulers.parallel())
                                .subscribe(tick -> nonInviteResponseCache.remove(txKey));
                    }

                    // Timer G & H: Retransmit 2xx final response for INVITE on UDP until ACK (RFC 3261 §13.3.1.4)
                    if (request.getMethod() == SipMethod.INVITE && resp.getStatusCode() >= 200 && resp.getStatusCode() < 300
                            && configuration.isUas2xxRetransmitEnabled() && request.getTransport() != SipTransport.TCP) {
                        startTimerGAndH(request, resp, originalSender);
                    }

                    if (activeTx != null) {
                        if (activeTx.markFinalResponseSent()) {
                            pendingTransactions.remove(activeTx.getTransactionKey());
                            activeTx.disposeAll();
                            sink.next(resp);
                        }
                    } else {
                        sink.next(resp);
                    }
                } else {
                    if (activeTx == null || (!activeTx.isCancelled() && !activeTx.isFinalResponseSent())) {
                        if (activeTx != null) {
                            activeTx.setLastProvisionalResponse(resp);
                        }
                        sink.next(resp);
                    }
                }
            };

            SipRoute matchedRoute = null;
            for (SipRoute route : routes) {
                if (route.matches(request)) {
                    matchedRoute = route;
                    break;
                }
            }

            if (matchedRoute == null) {
                if (tryingTimer != null) tryingTimer.dispose();
                if (activeTx != null) {
                    pendingTransactions.remove(activeTx.getTransactionKey());
                    activeTx.disposeAll();
                }
                handleNoRoute(request, resp -> {
                    sink.next(resp);
                });
                sink.complete();
                return;
            }

            try {
                Object result = matchedRoute.invoke(request, sessionManager);
                handleResult(request, result, terminalSender, tryingTimer, activeTx, sink);
            } catch (Throwable t) {
                if (tryingTimer != null) tryingTimer.dispose();
                if (activeTx != null) {
                    pendingTransactions.remove(activeTx.getTransactionKey());
                    activeTx.disposeAll();
                }
                handleDispatchError(t, request, resp -> {
                    sink.next(resp);
                });
                sink.complete();
            }
        });
    }

    private void handleResult(SipRequest request, Object result, Consumer<SipResponse> responseSender, Disposable timer, PendingServerTransaction activeTx, FluxSink<SipResponse> sink) {
        if (result == null) {
            if (timer != null) timer.dispose();
            sink.complete();
            return;
        }

        if (result instanceof SipResponse response) {
            responseSender.accept(response);
            sink.complete();
        } else if (result instanceof Publisher<?> publisher) {
            Disposable sub = Flux.from(publisher).subscribe(
                    item -> {
                        if (item instanceof SipResponse r) {
                            responseSender.accept(r);
                        }
                    },
                    error -> {
                        if (timer != null) timer.dispose();
                        if (activeTx != null) {
                            pendingTransactions.remove(activeTx.getTransactionKey());
                            activeTx.disposeAll();
                        }
                        handleDispatchError(error, request, resp -> {
                            responseSender.accept(resp);
                        });
                        sink.complete();
                    },
                    () -> {
                        if (timer != null) timer.dispose();
                        sink.complete();
                    }
            );
            if (activeTx != null) {
                activeTx.addDisposable(sub);
            }
        } else if (result instanceof CompletionStage<?> stage) {
            stage.whenComplete((item, error) -> {
                if (timer != null) timer.dispose();
                if (error != null) {
                    if (activeTx != null) {
                        pendingTransactions.remove(activeTx.getTransactionKey());
                        activeTx.disposeAll();
                    }
                    handleDispatchError(error, request, resp -> {
                        responseSender.accept(resp);
                    });
                    sink.complete();
                } else if (item instanceof SipResponse r) {
                    responseSender.accept(r);
                    sink.complete();
                } else {
                    sink.complete();
                }
            });
        } else {
            if (timer != null) timer.dispose();
            LOG.warn("Unrecognized return type from SIP handler: {}", result.getClass().getName());
            sink.complete();
        }
    }

    private void handleDispatchError(Throwable error, SipRequest request, Consumer<SipResponse> responseSender) {
        if (request.getMethod() == SipMethod.ACK) {
            return;
        }

        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof java.lang.reflect.InvocationTargetException) && cause.getCause() != null) {
            cause = cause.getCause();
        }

        SipErrorRoute bestRoute = findBestErrorRoute(cause);
        if (bestRoute != null) {
            try {
                Object errResult = bestRoute.invoke(cause, request, sessionManager);
                if (errResult instanceof SipResponse resp) {
                    responseSender.accept(resp);
                    return;
                } else if (errResult instanceof Publisher<?> pub) {
                    Flux.from(pub).subscribe(item -> {
                        if (item instanceof SipResponse r) responseSender.accept(r);
                    });
                    return;
                }
            } catch (Throwable handlerEx) {
                LOG.error("Exception executing @SipError handler for {}: {}", cause.getClass().getSimpleName(), handlerEx.getMessage(), handlerEx);
            }
        }

        LOG.error("Unhandled error processing SIP request: {} {}", request.getMethod(), request.getUri(), cause);
        if (cause instanceof IllegalArgumentException) {
            responseSender.accept(SipResponse.badRequest(request, cause.getMessage()));
        } else {
            responseSender.accept(SipResponse.serverError(request, cause.getMessage()));
        }
    }

    private SipErrorRoute findBestErrorRoute(Throwable cause) {
        SipErrorRoute best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (SipErrorRoute route : errorRoutes) {
            if (route.matches(cause)) {
                int distance = getInheritanceDistance(route.getExceptionType(), cause.getClass());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = route;
                }
            }
        }
        return best;
    }

    private int getInheritanceDistance(Class<?> target, Class<?> actual) {
        int distance = 0;
        Class<?> current = actual;
        while (current != null && target.isAssignableFrom(current)) {
            if (current.equals(target)) {
                return distance;
            }
            distance++;
            current = current.getSuperclass();
        }
        return distance;
    }

    public List<SipErrorRoute> getErrorRoutes() {
        return Collections.unmodifiableList(errorRoutes);
    }

    private void handleNoRoute(SipRequest request, Consumer<SipResponse> responseSender) {
        if (request.getMethod() == SipMethod.ACK) {
            // RFC 3261 Section 17.2.1: ACKs are not responded to
            LOG.debug("Unmatched ACK received, dropping as per RFC 3261.");
            return;
        }

        String allowedMethods = getAllowedMethods();

        if (request.getMethod() == SipMethod.OPTIONS) {
            // Default RFC 3261 response for OPTIONS
            SipResponse ok = SipResponse.ok(request);
            ok.getHeaders().set(SipHeaders.ALLOW, allowedMethods);
            responseSender.accept(ok);
            return;
        }

        metrics.requestRejected("no_route");

        // If the method is not supported by any matching route, reject with 405 Method Not Allowed + Allow header
        boolean uriMatchedByAnyRoute = routes.stream().anyMatch(r -> r.matchesUri(request));
        if (uriMatchedByAnyRoute) {
            LOG.info("Method {} not allowed for URI: {}", request.getMethod(), request.getUri());
            metrics.requestRejected("method_not_allowed");
            SipResponse methodNotAllowed = SipResponse.methodNotAllowed(request, allowedMethods);
            responseSender.accept(methodNotAllowed);
            return;
        }

        LOG.info("No matching route found for SIP request: {} {}", request.getMethod(), request.getUri());
        metrics.requestRejected("not_found");
        SipResponse notFound = SipResponse.notFound(request);
        responseSender.accept(notFound);
    }

    public int getPendingTransactionCount() {
        return pendingTransactions.size();
    }

    public SipMetrics getMetrics() {
        return metrics;
    }

    public String getAllowedMethods() {
        Set<String> methodNames = new LinkedHashSet<>();
        methodNames.add("INVITE");
        methodNames.add("ACK");
        methodNames.add("BYE");
        methodNames.add("CANCEL");
        methodNames.add("OPTIONS");
        for (SipRoute route : routes) {
            if (route.getCustomMethod() != null && !route.getCustomMethod().isEmpty()) {
                methodNames.add(route.getCustomMethod().toUpperCase(Locale.ROOT));
            } else if (route.getMethod() != null) {
                methodNames.add(route.getMethod().name());
            }
        }
        return String.join(", ", methodNames);
    }

    private List<String> getUnsupportedExtensions(String requireHeader) {
        if (requireHeader == null || requireHeader.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> unsupported = new ArrayList<>();
        String[] parts = requireHeader.split(",");
        for (String part : parts) {
            String tag = part.trim().toLowerCase(Locale.ROOT);
            if (!tag.isEmpty() && !SUPPORTED_EXTENSIONS.contains(tag)) {
                unsupported.add(part.trim());
            }
        }
        return unsupported;
    }

    public List<SipRoute> getRoutes() {
        return routes;
    }

    private void startTimerGAndH(SipRequest request, SipResponse response, Consumer<SipResponse> sender) {
        String key = request.getCallId();
        Pending2xxRetransmission pending = new Pending2xxRetransmission(key, request, response, sender);
        pending2xxRetransmissions.put(key, pending);

        long t1 = configuration.getT1Ms();
        long t2 = configuration.getT2Ms();
        long timerH = configuration.getTimerHDelayMs();

        scheduleTimerG(pending, t1, t2);

        Disposable hDisp = Mono.delay(Duration.ofMillis(timerH), timerScheduler)
                .subscribe(tick -> {
                    if (pending.getAckReceived().compareAndSet(false, true)) {
                        LOG.warn("Timer H expired for Call-ID: {}. No ACK received within {}ms. Terminating session.", key, timerH);
                        pending2xxRetransmissions.remove(key);
                        Disposable retr = pending.getRetransmitDisposable().getAndSet(null);
                        if (retr != null) retr.dispose();

                        SipSession session = sessionManager.getSession(key);
                        if (session != null) {
                            session.setState(SipSession.State.TERMINATED);
                        }
                        metrics.requestRejected("timer_h_ack_timeout");

                        // RFC 3261 §14.1 / §15: Send BYE to terminate the unacked dialog
                        sendByeForUnackedInvite(pending);
                    }
                });
        pending.setTimerHDisposable(hDisp);
    }

    private void scheduleTimerG(Pending2xxRetransmission pending, long currentDelay, long t2) {
        Disposable d = Mono.delay(Duration.ofMillis(currentDelay), timerScheduler)
                .subscribe(tick -> {
                    if (pending.getAckReceived().get()) {
                        return;
                    }
                    LOG.debug("Timer G: Retransmitting 2xx OK for Call-ID: {} (interval: {}ms)", pending.getCallId(), currentDelay);
                    pending.getSender().accept(pending.getResponse());

                    long nextDelay = Math.min(currentDelay * 2, t2);
                    scheduleTimerG(pending, nextDelay, t2);
                });
        pending.setRetransmitDisposable(d);
    }

    private void sendByeForUnackedInvite(Pending2xxRetransmission pending) {
        Consumer<SipResponse> sender = pending.getSender();
        if (!(sender instanceof SipMessageSender msgSender)) {
            return;
        }
        SipRequest origReq = pending.getOriginalRequest();
        SipResponse resp = pending.getResponse();
        if (origReq == null || resp == null) {
            return;
        }

        try {
            // Determine Request-URI: Contact from original INVITE, or fallback to From URI
            String targetUri = origReq.getContact();
            if (targetUri == null || targetUri.isEmpty()) {
                targetUri = origReq.getFrom();
            }
            if (targetUri != null) {
                targetUri = targetUri.replaceAll("^<|>$", "").split(";")[0];
            } else {
                targetUri = origReq.getUri().toString();
            }

            SipRequest bye = new SipRequest(SipMethod.BYE, SipUri.parse(targetUri));
            bye.setRemoteAddress(origReq.getRemoteAddress());
            bye.setTransport(origReq.getTransport());

            // In dialog: To is remote (original From), From is local (original To with tag)
            bye.getHeaders().set(SipHeaders.TO, origReq.getFrom());
            String localTo = resp.getTo();
            if (localTo == null || localTo.isEmpty()) {
                localTo = origReq.getTo() + ";tag=" + UUID.randomUUID().toString().substring(0, 8);
            }
            bye.getHeaders().set(SipHeaders.FROM, localTo);
            bye.getHeaders().set(SipHeaders.CALL_ID, origReq.getCallId());
            bye.getHeaders().set(SipHeaders.CSEQ, "1 BYE");
            bye.getHeaders().set(SipHeaders.MAX_FORWARDS, "70");

            // Topmost Via
            String host = "127.0.0.1";
            int port = 5060;
            if (origReq.getRemoteAddress() != null && origReq.getRemoteAddress().getAddress() != null) {
                host = origReq.getRemoteAddress().getAddress().getHostAddress();
            }
            String branch = "z9hG4bK-bye-" + UUID.randomUUID().toString().substring(0, 8);
            bye.getHeaders().set(SipHeaders.VIA, "SIP/2.0/UDP " + host + ":" + port + ";branch=" + branch + ";rport");

            LOG.info("Sending BYE for unacked INVITE Call-ID: {} to {}", origReq.getCallId(), targetUri);
            msgSender.sendRequest(bye);
        } catch (Exception e) {
            LOG.warn("Failed to send BYE for unacked INVITE Call-ID: {}: {}", origReq.getCallId(), e.getMessage());
        }
    }

    public int getPending2xxRetransmissionCount() {
        return pending2xxRetransmissions.size();
    }

    public boolean hasPending2xxRetransmission(String callId) {
        return pending2xxRetransmissions.containsKey(callId);
    }

    public int getNonInviteCachedResponseCount() {
        return nonInviteResponseCache.size();
    }

    public void clearTimerCaches() {
        for (Pending2xxRetransmission p : pending2xxRetransmissions.values()) {
            p.cancel();
        }
        pending2xxRetransmissions.clear();
        nonInviteResponseCache.clear();
    }
}
