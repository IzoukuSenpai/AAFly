package com.azurhosts.aafly.placeholder;

import com.azurhosts.aafly.AAFlyPlugin;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.FlightService;
import com.azurhosts.aafly.core.FlySpeedService;
import com.azurhosts.aafly.time.TimeMath;
import com.azurhosts.aafly.timedfly.TimedFlyService;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Calcul des valeurs des placeholders AAFly, <b>sans aucune dépendance à PlaceholderAPI</b>
 * (les expansions PAPI ne font que déléguer ici).
 *
 * <p><b>Threads :</b> PlaceholderAPI peut être interrogé hors du main thread (scoreboards, tab…).
 * Les profils sont confinés au main thread et l'API Bukkit ne doit pas y être appelée ailleurs (SPEC §11.2) :
 * <ul>
 *   <li>sur le main thread, les valeurs sont calculées directement ;</li>
 *   <li>hors main thread, seule une copie immuable mise en cache est lue. Le joueur est alors marqué
 *       « demandé » et une unique tâche synchrone (1 fois par seconde) rafraîchit le cache des seuls joueurs
 *       demandés hors main thread pendant les 30 dernières secondes.</li>
 * </ul>
 * Les valeurs renvoyées sont des données (jamais exécutées). Les durées formatées sont converties en
 * codes couleur legacy {@code §} pour être compatibles avec tous les consommateurs PAPI.
 */
public final class FlightPlaceholderResolver {

    /** Valeur renvoyée pour les compteurs entiers d'un joueur illimité. */
    public static final String UNLIMITED = "unlimited";

    private static final long ASYNC_INTEREST_MILLIS = TimeUnit.SECONDS.toMillis(30L);
    private static final int LEGACY_CACHE_MAX = 1024;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /**
     * Copie immuable de l'état de vol d'un joueur en ligne.
     *
     * @param remaining       temps restant (secondes)
     * @param enabled         vol AAFly actif (voulu et non suspendu)
     * @param flying          le joueur est en train de voler
     * @param unlimited       vol illimité
     * @param speedLevel      niveau de vitesse 1..10
     * @param cooldownSeconds recharge timed-fly restante (secondes)
     */
    public record Values(long remaining, boolean enabled, boolean flying, boolean unlimited, int speedLevel,
                         long cooldownSeconds) {
    }

    private final AAFlyPlugin plugin;
    private final Map<UUID, Values> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> asyncInterest = new ConcurrentHashMap<>();
    private final Map<String, String> legacyCache = new ConcurrentHashMap<>();
    private volatile boolean errorLogged;
    private @Nullable BukkitTask refresher;

    /**
     * @param plugin instance du plugin (accès paresseux aux services)
     */
    public FlightPlaceholderResolver(AAFlyPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Démarre la tâche de rafraîchissement du cache (main thread). Idempotent. */
    public void start() {
        if (refresher == null) {
            refresher = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, 20L);
        }
    }

    /** Arrête la tâche de rafraîchissement et vide les caches (main thread). Idempotent. */
    public void stop() {
        BukkitTask task = refresher;
        refresher = null;
        if (task != null) {
            task.cancel();
        }
        cache.clear();
        asyncInterest.clear();
        legacyCache.clear();
    }

    /** Vide le cache des durées converties (après un reload du format). */
    public void clearFormatCache() {
        legacyCache.clear();
    }

    // ------------------------------------------------------------------ placeholders

    /**
     * Placeholders {@code %aafly_<params>%} (SPEC §9).
     *
     * @param player joueur (peut être {@code null})
     * @param params partie après {@code aafly_}
     * @return valeur, chaîne vide si le joueur n'a pas de profil chargé, {@code null} si le placeholder est inconnu
     */
    public @Nullable String aafly(@Nullable OfflinePlayer player, String params) {
        String p = params == null ? "" : params.toLowerCase(Locale.ROOT);
        if (!isAaflyParam(p)) {
            return null;
        }
        Values v = values(player);
        if (v == null) {
            return "";
        }
        return switch (p) {
            case "remaining" -> formatted(v.remaining(), v.unlimited());
            case "remaining_seconds" -> unit(v, TimeUnit.SECONDS);
            case "remaining_minutes" -> unit(v, TimeUnit.MINUTES);
            case "remaining_hours" -> unit(v, TimeUnit.HOURS);
            case "remaining_days" -> unit(v, TimeUnit.DAYS);
            case "enabled" -> Boolean.toString(v.enabled());
            case "flying" -> Boolean.toString(v.flying());
            case "unlimited" -> Boolean.toString(v.unlimited());
            case "speed" -> Integer.toString(v.speedLevel());
            case "cooldown" -> formatted(v.cooldownSeconds(), false);
            case "cooldown_seconds" -> Long.toString(v.cooldownSeconds());
            default -> null;
        };
    }

    /**
     * Placeholders de compatibilité {@code %wfly_<params>%}.
     *
     * @param player joueur (peut être {@code null})
     * @param params partie après {@code wfly_}
     * @return valeur, chaîne vide si le joueur n'a pas de profil chargé, {@code null} si le placeholder est inconnu
     */
    public @Nullable String wfly(@Nullable OfflinePlayer player, String params) {
        String p = params == null ? "" : params.toLowerCase(Locale.ROOT);
        if (!isWflyParam(p)) {
            return null;
        }
        Values v = values(player);
        if (v == null) {
            return "";
        }
        return switch (p) {
            case "fly_remaining" -> formatted(v.remaining(), v.unlimited());
            case "fly_activate" -> Boolean.toString(v.enabled());
            case "fly_remaining_seconds" -> unit(v, TimeUnit.SECONDS);
            case "fly_remaining_minutes" -> unit(v, TimeUnit.MINUTES);
            case "fly_remaining_hours" -> unit(v, TimeUnit.HOURS);
            case "fly_remaining_days" -> unit(v, TimeUnit.DAYS);
            default -> null;
        };
    }

    private static boolean isAaflyParam(String p) {
        return switch (p) {
            case "remaining", "remaining_seconds", "remaining_minutes", "remaining_hours", "remaining_days",
                 "enabled", "flying", "unlimited", "speed", "cooldown", "cooldown_seconds" -> true;
            default -> false;
        };
    }

    private static boolean isWflyParam(String p) {
        return switch (p) {
            case "fly_remaining", "fly_activate", "fly_remaining_seconds", "fly_remaining_minutes",
                 "fly_remaining_hours", "fly_remaining_days" -> true;
            default -> false;
        };
    }

    private static String unit(Values v, TimeUnit unit) {
        return v.unlimited() ? UNLIMITED : Long.toString(TimeMath.toUnit(v.remaining(), unit));
    }

    /** Durée formatée (MiniMessage de configuration) convertie en texte legacy {@code §}. */
    private String formatted(long seconds, boolean unlimited) {
        try {
            String mm = plugin.timeFormatter().format(seconds, unlimited);
            String hit = legacyCache.get(mm);
            if (hit != null) {
                return hit;
            }
            String legacy = LEGACY.serialize(plugin.lang().parse(mm));
            if (legacyCache.size() >= LEGACY_CACHE_MAX) {
                legacyCache.clear();
            }
            legacyCache.put(mm, legacy);
            return legacy;
        } catch (RuntimeException e) {
            logOnce(e);
            return Long.toString(Math.max(0L, seconds));
        }
    }

    // ------------------------------------------------------------------ état

    /**
     * Valeurs d'un joueur : calculées sur le main thread, lues en cache ailleurs.
     *
     * @param player joueur
     * @return valeurs, ou {@code null} si le joueur n'est pas en ligne / pas encore chargé
     */
    public @Nullable Values values(@Nullable OfflinePlayer player) {
        if (player == null) {
            return null;
        }
        UUID id = player.getUniqueId();
        if (id == null) {
            return null;
        }
        if (!Bukkit.isPrimaryThread()) {
            asyncInterest.put(id, System.currentTimeMillis());
            return cache.get(id);
        }
        Player online = Bukkit.getPlayer(id);
        if (online == null) {
            cache.remove(id);
            return null;
        }
        Values v = compute(online);
        if (asyncInterest.containsKey(id)) {
            if (v == null) {
                cache.remove(id);
            } else {
                cache.put(id, v);
            }
        }
        return v;
    }

    /** Main thread : calcule l'état courant d'un joueur en ligne. */
    private @Nullable Values compute(Player player) {
        try {
            FlightService fs = plugin.flightService();
            FlightProfile profile = fs.profile(player.getUniqueId());
            if (profile == null) {
                return null;
            }
            long cooldown = 0L;
            TimedFlyService tf = plugin.timedFly();
            if (tf.enabled()) {
                cooldown = tf.cooldownRemaining(profile, Instant.now().getEpochSecond());
            }
            return new Values(profile.remainingSeconds(), fs.isEnabled(player), player.isFlying(),
                    fs.hasUnlimited(player), FlySpeedService.speedToLevel(profile.flySpeed()), cooldown);
        } catch (RuntimeException e) {
            // Services pas encore prêts (démarrage) ou erreur d'un service : aucune valeur.
            logOnce(e);
            return null;
        }
    }

    /** Tâche synchrone : rafraîchit les joueurs demandés hors main thread récemment. */
    private void refresh() {
        if (asyncInterest.isEmpty()) {
            if (!cache.isEmpty()) {
                cache.clear();
            }
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = asyncInterest.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            UUID id = e.getKey();
            Player online = now - e.getValue() > ASYNC_INTEREST_MILLIS ? null : Bukkit.getPlayer(id);
            Values v = online == null ? null : compute(online);
            if (v == null) {
                cache.remove(id);
                if (online == null) {
                    it.remove();
                }
            } else {
                cache.put(id, v);
            }
        }
        // Entrées orphelines (joueur retiré de l'intérêt entre-temps).
        cache.keySet().removeIf(id -> !asyncInterest.containsKey(id));
    }

    private void logOnce(RuntimeException e) {
        // IllegalStateException : services pas encore initialisés (démarrage/arrêt) → silencieux.
        if (errorLogged || e instanceof IllegalStateException) {
            return;
        }
        errorLogged = true;
        plugin.getLogger().log(Level.WARNING, "Erreur lors du calcul d'un placeholder AAFly (signalée une seule fois).", e);
    }
}
