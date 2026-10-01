package net.pilgrim.sip.router;

import net.pilgrim.sip.model.SipRequest;
import net.pilgrim.sip.model.SipResponse;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Tracks an in-flight server transaction (typically an INVITE) awaiting completion,
 * susceptible to CANCEL matching per RFC 3261 §9.2 and §17.2.3.
 */
public class PendingServerTransaction {

    private final String transactionKey;
    private final SipRequest request;
    private final Consumer<SipResponse> originalSender;
    private final AtomicBoolean finalResponseSent = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Disposable> disposables = new CopyOnWriteArrayList<>();
    private final long createdAtNanos = System.nanoTime();
    private volatile SipResponse lastProvisionalResponse;

    public PendingServerTransaction(String transactionKey, SipRequest request, Consumer<SipResponse> originalSender) {
        this.transactionKey = transactionKey;
        this.request = request;
        this.originalSender = originalSender;
    }

    public String getTransactionKey() {
        return transactionKey;
    }

    public SipRequest getRequest() {
        return request;
    }

    public Consumer<SipResponse> getOriginalSender() {
        return originalSender;
    }

    public boolean isFinalResponseSent() {
        return finalResponseSent.get();
    }

    public boolean markFinalResponseSent() {
        return finalResponseSent.compareAndSet(false, true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean cancel() {
        if (cancelled.compareAndSet(false, true)) {
            disposeAll();
            return true;
        }
        return false;
    }

    public void addDisposable(Disposable disposable) {
        if (disposable != null) {
            if (cancelled.get()) {
                disposable.dispose();
            } else {
                disposables.add(disposable);
            }
        }
    }

    public void disposeAll() {
        for (Disposable d : disposables) {
            try {
                d.dispose();
            } catch (Exception ignored) {}
        }
        disposables.clear();
    }

    public long getAgeMillis() {
        return (System.nanoTime() - createdAtNanos) / 1_000_000;
    }

    public SipResponse getLastProvisionalResponse() {
        return lastProvisionalResponse;
    }

    public void setLastProvisionalResponse(SipResponse response) {
        this.lastProvisionalResponse = response;
    }
}
