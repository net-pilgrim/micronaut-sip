package net.pilgrim.sip.filter;

/**
 * Thread-safe token bucket for rate-limiting operations per client IP.
 */
public class TokenBucket {

    private final double capacity;
    private final double refillRatePerSecond;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double capacity, double refillRatePerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Attempts to consume a single token.
     *
     * @return true if consumed, false if bucket is exhausted
     */
    public synchronized boolean tryConsume() {
        return tryConsume(1.0);
    }

    /**
     * Attempts to consume the specified number of tokens.
     *
     * @param tokensToConsume tokens requested
     * @return true if consumed, false if bucket has insufficient tokens
     */
    public synchronized boolean tryConsume(double tokensToConsume) {
        refill();
        if (tokens >= tokensToConsume) {
            tokens -= tokensToConsume;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos > 0) {
            double deltaTokens = (elapsedNanos / 1_000_000_000.0) * refillRatePerSecond;
            tokens = Math.min(capacity, tokens + deltaTokens);
            lastRefillNanos = now;
        }
    }

    public synchronized double getAvailableTokens() {
        refill();
        return tokens;
    }

    public synchronized long getLastActivityNanos() {
        return lastRefillNanos;
    }

    public synchronized boolean isFull() {
        refill();
        return tokens >= capacity;
    }

    public double getCapacity() {
        return capacity;
    }

    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }
}
