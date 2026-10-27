package com.azurhosts.aafly.storage;

import com.azurhosts.aafly.core.FlightProfile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Cache LRU des profils hors-ligne (SPEC §12) : {@value #DEFAULT_MAX_SIZE} entrées max, expiration
 * {@value #DEFAULT_TTL_MILLIS} ms après le dernier accès.
 *
 * <p>Non thread-safe : utilisé uniquement sur le main thread. Les entrées évincées sont rendues à
 * l'appelant, qui décide de leur sort (un profil {@code dirty} évincé doit encore être écrit).
 */
public final class OfflineProfileCache {

    /** Capacité par défaut. */
    public static final int DEFAULT_MAX_SIZE = 256;
    /** Durée de vie par défaut après le dernier accès (10 minutes). */
    public static final long DEFAULT_TTL_MILLIS = 600_000L;

    private static final class Entry {
        final FlightProfile profile;
        long lastAccessMillis;

        Entry(FlightProfile profile, long now) {
            this.profile = profile;
            this.lastAccessMillis = now;
        }
    }

    private final int maxSize;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);

    /** Cache aux valeurs par défaut, horloge système. */
    public OfflineProfileCache() {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL_MILLIS, System::currentTimeMillis);
    }

    /**
     * @param maxSize   capacité (&gt; 0)
     * @param ttlMillis durée de vie après le dernier accès (&gt; 0)
     * @param clock     horloge en millisecondes (injectable pour les tests)
     */
    public OfflineProfileCache(int maxSize, long ttlMillis, LongSupplier clock) {
        if (maxSize <= 0 || ttlMillis <= 0L) {
            throw new IllegalArgumentException("maxSize et ttlMillis doivent être > 0");
        }
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param uuid joueur
     * @return profil (accès rafraîchi) ou {@code null} s'il est absent ou expiré
     */
    public @Nullable FlightProfile get(UUID uuid) {
        Entry e = entries.get(uuid);
        if (e == null) {
            return null;
        }
        long now = clock.getAsLong();
        if (now - e.lastAccessMillis >= ttlMillis && !e.profile.isDirty()) {
            entries.remove(uuid);
            return null;
        }
        e.lastAccessMillis = now;
        return e.profile;
    }

    /**
     * @param uuid joueur
     * @return profil sans rafraîchir l'accès ni l'ordre LRU, ou {@code null}
     */
    public @Nullable FlightProfile peek(UUID uuid) {
        // LinkedHashMap en ordre d'accès : get() modifierait l'ordre, on parcourt donc la vue des valeurs.
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getKey().equals(uuid)) {
                return e.getValue().profile;
            }
        }
        return null;
    }

    /**
     * Ajoute ou remplace un profil.
     *
     * @param profile profil hors-ligne
     * @return profils évincés pour dépassement de capacité (jamais celui ajouté)
     */
    public List<FlightProfile> put(FlightProfile profile) {
        Objects.requireNonNull(profile, "profile");
        entries.put(profile.uuid(), new Entry(profile, clock.getAsLong()));
        if (entries.size() <= maxSize) {
            return List.of();
        }
        List<FlightProfile> evicted = new ArrayList<>(1);
        Iterator<Map.Entry<UUID, Entry>> it = entries.entrySet().iterator();
        while (entries.size() > maxSize && it.hasNext()) {
            Map.Entry<UUID, Entry> eldest = it.next();
            if (eldest.getKey().equals(profile.uuid())) {
                continue;
            }
            it.remove();
            evicted.add(eldest.getValue().profile);
        }
        return evicted;
    }

    /**
     * @param uuid joueur
     * @return profil retiré, ou {@code null}
     */
    public @Nullable FlightProfile remove(UUID uuid) {
        Entry e = entries.remove(uuid);
        return e == null ? null : e.profile;
    }

    /**
     * Retire les entrées expirées.
     *
     * @return profils évincés (y compris {@code dirty}, que l'appelant doit faire écrire)
     */
    public List<FlightProfile> evictExpired() {
        long now = clock.getAsLong();
        List<FlightProfile> evicted = new ArrayList<>();
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (now - e.lastAccessMillis < ttlMillis) {
                break; // ordre d'accès : les suivants sont plus récents
            }
            it.remove();
            evicted.add(e.profile);
        }
        return evicted;
    }

    /**
     * @return vue non modifiable des profils en cache (ordre LRU, le plus ancien d'abord)
     */
    public Collection<FlightProfile> values() {
        List<FlightProfile> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) {
            out.add(e.profile);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * @return nombre d'entrées
     */
    public int size() {
        return entries.size();
    }

    /** Vide le cache. */
    public void clear() {
        entries.clear();
    }
}
