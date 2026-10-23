package net.pilgrim.sip.util;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Provides Java Virtual Thread execution and Reactor integration for non-blocking
 * asynchronous event processing across SIP and media sessions.
 */
public final class VirtualThreads {

    private static final ExecutorService EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("sip-vworker-", 0).factory()
    );

    private static final Scheduler SCHEDULER = Schedulers.fromExecutor(EXECUTOR);

    private VirtualThreads() {}

    public static ExecutorService executor() {
        return EXECUTOR;
    }

    public static Scheduler scheduler() {
        return SCHEDULER;
    }

    public static void run(Runnable task) {
        EXECUTOR.execute(task);
    }

    public static <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, EXECUTOR);
    }
}
