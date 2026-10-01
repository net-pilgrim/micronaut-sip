package net.pilgrim.sip.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages per-IP token buckets with bounded cache size and idle entry cleanup.
 */
public class IpRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(IpRateLimiter.class);

    private final double requestsPerSecond;
    private final int burstCapacity;
    private final int maxTrackedIps;
    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public IpRateLimiter(double requestsPerSecond, int burstCapacity, int maxTrackedIps) {
        this.requestsPerSecond = requestsPerSecond;
        this.burstCapacity = burstCapacity;
        this.maxTrackedIps = maxTrackedIps > 0 ? maxTrackedIps : 10000;
    }

    /**
     * Checks if a request from the given IP address is allowed by consuming 1 token.
     *
     * @param ipKey the IP string identifying the client
     * @return true if permitted, false if rate limit is exceeded
     */
    public boolean tryConsume(String ipKey) {
        if (ipKey == null || ipKey.isEmpty()) {
            return true;
        }

        if (buckets.size() >= maxTrackedIps) {
            evictIdleBuckets();
        }

        TokenBucket bucket = buckets.computeIfAbsent(ipKey, k -> new TokenBucket(burstCapacity, requestsPerSecond));
        return bucket.tryConsume();
    }

    private void evictIdleBuckets() {
        long now = System.nanoTime();
        long idleThreshold = TimeUnit.SECONDS.toNanos(60);
        Iterator<Map.Entry<String, TokenBucket>> it = buckets.entrySet().iterator();
        int evicted = 0;

        while (it.hasNext() && buckets.size() > (maxTrackedIps * 3 / 4)) {
            Map.Entry<String, TokenBucket> entry = it.next();
            TokenBucket b = entry.getValue();
            if (b.isFull() && (now - b.getLastActivityNanos()) > idleThreshold) {
                it.remove();
                evicted++;
            }
        }

        if (evicted > 0) {
            LOG.debug("Evicted {} idle rate limiter IP buckets", evicted);
        }
    }

    public int getTrackedIpCount() {
        return buckets.size();
    }

    public void reset() {
        buckets.clear();
    }

    public double getRequestsPerSecond() {
        return requestsPerSecond;
    }

    public int getBurstCapacity() {
        return burstCapacity;
    }
}
