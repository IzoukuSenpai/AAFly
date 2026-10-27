package com.azurhosts.aafly.rules;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Cache des décisions {@code fly-rules} par joueur, valable {@code ttlMillis}.
 * Main thread uniquement (aucune synchronisation). Borné : vidé s'il dépasse {@value #MAX_ENTRIES} entrées
 * (les entrées sont normalement retirées à la déconnexion via {@link #invalidate(UUID)}).
 */
public final class DecisionCache {

    /** Taille au-delà de laquelle le cache est vidé (garde-fou mémoire). */
    public static final int MAX_ENTRIES = 4096;

    private record Entry(RuleDecision decision, long expiresAt) {
    }

    private final Map<UUID, Entry> entries = new HashMap<>();
    private final LongSupplier clockMillis;
    private long ttlMillis;

    /**
     * @param clockMillis horloge en millisecondes
     * @param ttlMillis   durée de validité (≤ 0 : cache désactivé)
     */
    public DecisionCache(LongSupplier clockMillis, long ttlMillis) {
        this.clockMillis = clockMillis;
        this.ttlMillis = ttlMillis;
    }

    /**
     * @param ttlMillis nouvelle durée de validité ; vide le cache
     */
    public void ttl(long ttlMillis) {
        this.ttlMillis = ttlMillis;
        entries.clear();
    }

    /**
     * @param uuid joueur
     * @return décision encore valide, ou {@code null}
     */
    public @Nullable RuleDecision get(UUID uuid) {
        if (ttlMillis <= 0L) {
            return null;
        }
        Entry e = entries.get(uuid);
        if (e == null) {
            return null;
        }
        if (clockMillis.getAsLong() >= e.expiresAt()) {
            entries.remove(uuid);
            return null;
        }
        return e.decision();
    }

    /**
     * @param uuid     joueur
     * @param decision décision à mémoriser
     */
    public void put(UUID uuid, RuleDecision decision) {
        if (ttlMillis <= 0L) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(uuid)) {
            entries.clear();
        }
        entries.put(uuid, new Entry(decision, clockMillis.getAsLong() + ttlMillis));
    }

    /**
     * @param uuid joueur à oublier
     */
    public void invalidate(UUID uuid) {
        entries.remove(uuid);
    }

    /** Vide le cache. */
    public void clear() {
        entries.clear();
    }

    /**
     * @return nombre d'entrées
     */
    public int size() {
        return entries.size();
    }
}
