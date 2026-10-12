package net.pilgrim.vxml.storage;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Global registry holder for {@link ProvStorageService}, enabling Java URL/URI
 * stream handler providers to resolve {@code prov://} streams transparently.
 */
public final class ProvStorageRegistry {

    private static final AtomicReference<ProvStorageService> INSTANCE = new AtomicReference<>();

    private ProvStorageRegistry() {}

    public static ProvStorageService get() {
        ProvStorageService current = INSTANCE.get();
        if (current == null) {
            ProvStorageService defaultService = new ProvStorageService();
            INSTANCE.compareAndSet(null, defaultService);
            return INSTANCE.get();
        }
        return current;
    }

    public static void set(ProvStorageService service) {
        if (service != null) {
            INSTANCE.set(service);
        }
    }
}
