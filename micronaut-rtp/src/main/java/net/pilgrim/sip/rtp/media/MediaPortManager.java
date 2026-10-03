package net.pilgrim.sip.rtp.media;

import java.util.BitSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe port manager that dynamically allocates even UDP ports for RTP
 * and reserves the consecutive odd port for RTCP per RFC 3550 Section 11.
 */
public final class MediaPortManager {

    private final int portRangeStart;
    private final int portRangeEnd;
    private final int totalPortPairs;
    private final BitSet allocatedPairs;
    private final ReentrantLock lock = new ReentrantLock();

    private int nextSearchIndex = 0;
    private int activeCount = 0;

    public MediaPortManager() {
        this(10000, 20000);
    }

    public MediaPortManager(int portRangeStart, int portRangeEnd) {
        if (portRangeStart <= 1024 || portRangeStart >= 65534) {
            throw new IllegalArgumentException("portRangeStart must be between 1025 and 65533");
        }
        if (portRangeEnd <= portRangeStart || portRangeEnd > 65535) {
            throw new IllegalArgumentException("portRangeEnd must be > portRangeStart and <= 65535");
        }

        // Ensure start is even
        this.portRangeStart = (portRangeStart % 2 == 0) ? portRangeStart : portRangeStart + 1;
        this.portRangeEnd = portRangeEnd;

        int span = this.portRangeEnd - this.portRangeStart + 1;
        this.totalPortPairs = span / 2;
        if (this.totalPortPairs <= 0) {
            throw new IllegalArgumentException("Port range must contain at least one RTP/RTCP pair");
        }
        this.allocatedPairs = new BitSet(totalPortPairs);
    }

    /**
     * Allocates an even UDP port for RTP (and reserves port+1 for RTCP).
     *
     * @return allocated even port number
     * @throws IllegalStateException if all ports in the pool are exhausted
     */
    public int allocatePort() {
        lock.lock();
        try {
            int searched = 0;
            while (searched < totalPortPairs) {
                int pairIndex = nextSearchIndex;
                nextSearchIndex = (nextSearchIndex + 1) % totalPortPairs;
                searched++;

                if (!allocatedPairs.get(pairIndex)) {
                    allocatedPairs.set(pairIndex);
                    activeCount++;
                    return portRangeStart + (pairIndex * 2);
                }
            }
            throw new IllegalStateException("All RTP media ports exhausted in range "
                    + portRangeStart + "-" + portRangeEnd + " (" + totalPortPairs + " concurrent pairs)");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Releases an allocated RTP port (and its RTCP companion).
     */
    public void releasePort(int port) {
        if (port < portRangeStart || port > portRangeEnd) {
            return;
        }
        int pairIndex = (port - portRangeStart) / 2;
        lock.lock();
        try {
            if (pairIndex >= 0 && pairIndex < totalPortPairs && allocatedPairs.get(pairIndex)) {
                allocatedPairs.clear(pairIndex);
                activeCount = Math.max(0, activeCount - 1);
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isAllocated(int port) {
        if (port < portRangeStart || port > portRangeEnd) {
            return false;
        }
        int pairIndex = (port - portRangeStart) / 2;
        lock.lock();
        try {
            return pairIndex >= 0 && pairIndex < totalPortPairs && allocatedPairs.get(pairIndex);
        } finally {
            lock.unlock();
        }
    }

    public int getAvailablePortCount() {
        lock.lock();
        try {
            return (totalPortPairs - activeCount) * 2;
        } finally {
            lock.unlock();
        }
    }

    public int getActiveSessionCount() {
        lock.lock();
        try {
            return activeCount;
        } finally {
            lock.unlock();
        }
    }

    public int getPortRangeStart() {
        return portRangeStart;
    }

    public int getPortRangeEnd() {
        return portRangeEnd;
    }

    public int getTotalPortPairs() {
        return totalPortPairs;
    }
}
