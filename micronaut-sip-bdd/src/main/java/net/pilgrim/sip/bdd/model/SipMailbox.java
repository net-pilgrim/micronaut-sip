package net.pilgrim.sip.bdd.model;

import net.pilgrim.sip.model.SipMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Thread-safe asynchronous mailbox for a VirtualUserAgent.
 * Buffers received SIP messages and provides reactive predicates and timeout awaiters.
 */
public class SipMailbox {

    private static final Logger LOG = LoggerFactory.getLogger(SipMailbox.class);

    private final String actorName;
    private final List<SipMessage> allMessages = new CopyOnWriteArrayList<>();
    private final Set<SipMessage> handledMessages = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public SipMailbox(String actorName) {
        this.actorName = actorName;
    }

    /**
     * Ingests a received SIP message into the mailbox.
     */
    public synchronized void add(SipMessage message) {
        allMessages.add(message);
        notifyAll();
    }

    /**
     * Awaits a message matching the predicate within the timeout.
     * Checks already buffered unhandled messages and waits for new arrivals if not yet present.
     */
    public Mono<SipMessage> awaitMessage(Predicate<SipMessage> predicate, Duration timeout) {
        return Mono.fromCallable(() -> {
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            synchronized (this) {
                while (true) {
                    for (SipMessage msg : allMessages) {
                        if (!handledMessages.contains(msg) && predicate.test(msg)) {
                            handledMessages.add(msg);
                            return msg;
                        }
                    }
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        throw new TimeoutException(String.format(
                                "Actor '%s' did not receive expected SIP message within %s. Total received: %d",
                                actorName, timeout, allMessages.size()));
                    }
                    try {
                        wait(Math.min(remaining, 100));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for SIP message", e);
                    }
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Asserts that no messages matching the predicate arrive within the quiet period.
     */
    public Mono<Void> assertQuietPeriod(Predicate<SipMessage> predicate, Duration quietPeriod) {
        return Mono.fromRunnable(() -> {
            long deadline = System.currentTimeMillis() + quietPeriod.toMillis();
            synchronized (this) {
                while (true) {
                    for (SipMessage msg : allMessages) {
                        if (!handledMessages.contains(msg) && predicate.test(msg)) {
                            throw new AssertionError(String.format(
                                    "Actor '%s' received unexpected message during quiet check: %s",
                                    actorName, msg));
                        }
                    }
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        return;
                    }
                    try {
                        wait(Math.min(remaining, 50));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during quiet check", e);
                    }
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public synchronized List<SipMessage> getAllMessages() {
        return Collections.unmodifiableList(new CopyOnWriteArrayList<>(allMessages));
    }

    public synchronized List<SipMessage> getUnhandledMessages() {
        return allMessages.stream()
                .filter(msg -> !handledMessages.contains(msg))
                .toList();
    }

    public synchronized void clear() {
        allMessages.clear();
        handledMessages.clear();
        notifyAll();
    }
}
