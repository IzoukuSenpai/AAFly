package com.azurhosts.aafly.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.core.FlightProfile;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OfflineProfileCacheTest {

    private final AtomicLong clock = new AtomicLong(1_000L);

    private static FlightProfile profile() {
        return new FlightProfile(UUID.randomUUID(), "p", 10L, false, 0L, 0L, 0.1D);
    }

    @Test
    void evictsLeastRecentlyUsedBeyondCapacity() {
        OfflineProfileCache cache = new OfflineProfileCache(2, 60_000L, clock::get);
        FlightProfile a = profile();
        FlightProfile b = profile();
        FlightProfile c = profile();
        cache.put(a);
        cache.put(b);
        assertSame(a, cache.get(a.uuid())); // a devient le plus récent
        List<FlightProfile> evicted = cache.put(c);
        assertEquals(List.of(b), evicted);
        assertEquals(2, cache.size());
        assertNull(cache.get(b.uuid()));
    }

    @Test
    void expiresAfterTtlSinceLastAccess() {
        OfflineProfileCache cache = new OfflineProfileCache(10, 1_000L, clock::get);
        FlightProfile a = profile();
        FlightProfile b = profile();
        cache.put(a);
        clock.addAndGet(600L);
        cache.put(b);
        clock.addAndGet(500L); // a : 1100 ms, b : 500 ms
        assertEquals(List.of(a), cache.evictExpired());
        assertSame(b, cache.get(b.uuid()));
        clock.addAndGet(999L);
        assertTrue(cache.evictExpired().isEmpty(), "accès rafraîchi par get()");
    }

    @Test
    void expiredCleanEntryIsNotReturnedButDirtyOneIs() {
        OfflineProfileCache cache = new OfflineProfileCache(10, 1_000L, clock::get);
        FlightProfile clean = profile();
        FlightProfile dirty = profile();
        dirty.markDirty();
        cache.put(clean);
        cache.put(dirty);
        clock.addAndGet(5_000L);
        assertNull(cache.get(clean.uuid()));
        assertSame(dirty, cache.get(dirty.uuid()), "un profil non sauvegardé n'est jamais perdu silencieusement");
    }

    @Test
    void peekDoesNotChangeLruOrder() {
        OfflineProfileCache cache = new OfflineProfileCache(2, 60_000L, clock::get);
        FlightProfile a = profile();
        FlightProfile b = profile();
        cache.put(a);
        cache.put(b);
        assertSame(a, cache.peek(a.uuid()));
        assertEquals(List.of(a), cache.put(profile()));
    }
}
