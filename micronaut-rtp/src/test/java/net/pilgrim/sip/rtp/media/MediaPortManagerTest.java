package net.pilgrim.sip.rtp.media;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaPortManagerTest {

    @Test
    void allocatesEvenPortsAndTracksState() {
        MediaPortManager manager = new MediaPortManager(10000, 10020);

        int p1 = manager.allocatePort();
        int p2 = manager.allocatePort();

        assertTrue(p1 % 2 == 0, "Port must be even");
        assertTrue(p2 % 2 == 0, "Port must be even");
        assertTrue(p1 >= 10000 && p1 <= 10020);
        assertTrue(p2 >= 10000 && p2 <= 10020);
        assertTrue(p1 != p2);

        assertTrue(manager.isAllocated(p1));
        assertTrue(manager.isAllocated(p2));
        assertEquals(2, manager.getActiveSessionCount());

        manager.releasePort(p1);
        assertFalse(manager.isAllocated(p1));
        assertEquals(1, manager.getActiveSessionCount());

        int p3 = manager.allocatePort();
        assertTrue(p3 % 2 == 0);
        assertEquals(2, manager.getActiveSessionCount());
    }

    @Test
    void supportsHighConcurrencyWithoutDuplicates() {
        // Range with 100 pairs = 200 ports
        MediaPortManager manager = new MediaPortManager(20000, 20200);
        Set<Integer> allocated = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            int port = manager.allocatePort();
            assertTrue(port % 2 == 0);
            assertTrue(allocated.add(port), "Port " + port + " was allocated twice!");
        }

        assertEquals(100, manager.getActiveSessionCount());

        // Pool exhaustion test
        assertThrows(IllegalStateException.class, manager::allocatePort);

        // Release all
        for (int port : allocated) {
            manager.releasePort(port);
        }
        assertEquals(0, manager.getActiveSessionCount());

        // Reallocate
        int newPort = manager.allocatePort();
        assertTrue(allocated.contains(newPort));
    }
}
