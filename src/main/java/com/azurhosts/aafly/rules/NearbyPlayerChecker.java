package com.azurhosts.aafly.rules;

import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Détection d'un joueur « ennemi » proche (SPEC §4, section {@code pvp}).
 *
 * <p>Un joueur proche (rayon {@code pvp.radius}, 1–64) est ignoré s'il s'agit du joueur lui-même, d'un
 * spectateur, d'un joueur invisible pour lui (vanish), d'un NPC (métadonnée {@code NPC}), ou d'un allié :
 * même valeur, non vide et résolue, pour <b>tous</b> les {@code pvp.bypass-placeholders}. Sans placeholder
 * de contournement (ou sans PlaceholderAPI), tout joueur proche est un ennemi : un placeholder non résolu
 * ne rend jamais deux joueurs alliés (choix sûr).
 *
 * <p>Résultat mis en cache {@code pvp.check-interval-seconds} par joueur. Main thread uniquement.
 *
 * <p>Note : le plugin utilise {@link com.azurhosts.aafly.core.NearbyEnemyDetector} pour la détection ;
 * seule la règle d'alliance {@link #allied} est partagée. Si cette classe est instanciée un jour, appeler
 * {@link #invalidate(UUID)} à la déconnexion pour ne pas conserver d'entrée par joueur parti.
 */
public final class NearbyPlayerChecker {

    /** Rayon minimal. */
    public static final int MIN_RADIUS = 1;
    /** Rayon maximal. */
    public static final int MAX_RADIUS = 64;

    private record Cached(boolean enemy, long expiresAt) {
    }

    private final PlaceholderBridge placeholders;
    private final LongSupplier clockMillis;
    private final Map<UUID, Cached> cache = new HashMap<>();
    private volatile boolean enabled;
    private volatile int radius = 5;
    private volatile long intervalMillis = 1_000L;
    private volatile List<String> bypassPlaceholders = List.of();

    /**
     * @param placeholders pont PlaceholderAPI
     * @param clockMillis  horloge en millisecondes
     */
    public NearbyPlayerChecker(PlaceholderBridge placeholders, LongSupplier clockMillis) {
        this.placeholders = Objects.requireNonNull(placeholders, "placeholders");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /**
     * @param placeholders pont PlaceholderAPI
     */
    public NearbyPlayerChecker(PlaceholderBridge placeholders) {
        this(placeholders, System::currentTimeMillis);
    }

    /**
     * Applique la section {@code pvp} (rayon borné à [1, 64]) et vide le cache.
     *
     * @param settings réglages validés
     */
    public void reload(Settings settings) {
        Settings.PvpSection pvp = settings.pvp();
        this.radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, pvp.radius()));
        this.intervalMillis = Math.max(1, pvp.checkIntervalSeconds()) * 1_000L;
        this.bypassPlaceholders = List.copyOf(pvp.bypassPlaceholders());
        this.enabled = pvp.enabled();
        cache.clear();
    }

    /**
     * @return {@code true} si la détection est activée
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * @return rayon effectif
     */
    public int radius() {
        return radius;
    }

    /**
     * Indique si un ennemi est proche (avec cache). Toujours {@code false} si {@code pvp.enabled: false}.
     *
     * @param player joueur en vol
     * @return {@code true} si un ennemi est dans le rayon
     */
    public boolean hasEnemyNearby(Player player) {
        if (!enabled) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        long now = clockMillis.getAsLong();
        Cached c = cache.get(uuid);
        if (c != null && now < c.expiresAt()) {
            return c.enemy();
        }
        boolean enemy = findEnemy(player) != null;
        if (cache.size() >= DecisionCache.MAX_ENTRIES && !cache.containsKey(uuid)) {
            cache.clear();
        }
        cache.put(uuid, new Cached(enemy, now + intervalMillis));
        return enemy;
    }

    /**
     * Recherche immédiate (sans cache) du premier ennemi proche.
     *
     * @param player joueur en vol
     * @return un ennemi proche, ou {@code null}
     */
    public @Nullable Player findEnemy(Player player) {
        if (!enabled) {
            return null;
        }
        List<String> keys = bypassPlaceholders;
        List<String> selfValues = null;
        for (Player other : player.getLocation().getNearbyPlayers(radius)) {
            if (other.equals(player) || !other.isValid() || other.getGameMode() == GameMode.SPECTATOR
                    || other.hasMetadata("NPC") || !player.canSee(other)) {
                continue;
            }
            if (keys.isEmpty()) {
                return other;
            }
            if (selfValues == null) {
                selfValues = resolveAll(player, keys);
            }
            if (!allied(keys, selfValues, resolveAll(other, keys))) {
                return other;
            }
        }
        return null;
    }

    private List<String> resolveAll(Player player, List<String> keys) {
        List<String> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            out.add(placeholders.apply(player, key));
        }
        return out;
    }

    /**
     * Règle d'alliance (pure, testable) : alliés si, pour chaque placeholder, les deux valeurs sont
     * identiques, non vides et effectivement résolues (différentes du placeholder brut).
     *
     * @param keys        placeholders bruts
     * @param selfValues  valeurs résolues pour le joueur
     * @param otherValues valeurs résolues pour l'autre joueur
     * @return {@code true} si alliés
     */
    public static boolean allied(List<String> keys, List<String> selfValues, List<String> otherValues) {
        if (keys.isEmpty() || keys.size() != selfValues.size() || keys.size() != otherValues.size()) {
            return false;
        }
        for (int i = 0; i < keys.size(); i++) {
            String raw = keys.get(i);
            String a = selfValues.get(i);
            String b = otherValues.get(i);
            if (a == null || b == null || a.isBlank() || a.equals(raw) || !a.equals(b)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param uuid joueur à oublier (déconnexion, changement de monde)
     */
    public void invalidate(UUID uuid) {
        cache.remove(uuid);
    }

    /** Vide le cache. */
    public void clear() {
        cache.clear();
    }
}
