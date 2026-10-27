package com.azurhosts.aafly.core;

import com.azurhosts.aafly.api.ThreatResolver;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import com.azurhosts.aafly.rules.NearbyPlayerChecker;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Résolveur de menace par défaut d'AAFly (SPEC §4 « Nearby / PVP », SPEC-phase2 A6) : règle intégrée
 * {@code pvp.bypass-placeholders}, appliquée quand aucun résolveur tiers ne s'est prononcé.
 *
 * <p>Deux joueurs sont alliés s'ils partagent, pour <b>tous</b> les placeholders configurés, la même valeur non
 * vide et effectivement résolue. Choix sûr : une valeur vide ou non résolue, une liste vide ou PlaceholderAPI
 * absent ne rendent jamais deux joueurs alliés ({@link Verdict#THREAT}).
 *
 * <p>Performance (A6) : les valeurs résolues sont mises en cache {@link #CACHE_TICKS} ticks (2 s) par joueur ;
 * le cache est vidé quand la liste de placeholders change ({@code /aafly reload}) et purgé de ses entrées
 * expirées au-delà de {@link #PRUNE_SIZE} entrées. Main thread uniquement.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class BuiltinThreatResolver implements ThreatResolver {

    /** Durée de validité (ticks serveur) des valeurs de placeholders résolues : 2 s. */
    static final int CACHE_TICKS = 40;
    /** Taille au-delà de laquelle les entrées expirées sont purgées. */
    static final int PRUNE_SIZE = 256;

    /** Valeurs résolues d'un joueur et tick d'expiration. */
    private record Cached(String[] values, int expiresAtTick) {
    }

    private final Supplier<Settings> settings;
    private final PlaceholderBridge placeholders;
    private final IntSupplier currentTick;
    private final Map<UUID, Cached> cache = new HashMap<>();
    private List<String> cacheInputs = List.of();

    /**
     * @param settings     réglages courants (relus à chaque appel : compatible {@code /aafly reload})
     * @param placeholders pont PlaceholderAPI
     */
    public BuiltinThreatResolver(Supplier<Settings> settings, PlaceholderBridge placeholders) {
        this(settings, placeholders, Bukkit::getCurrentTick);
    }

    BuiltinThreatResolver(Supplier<Settings> settings, PlaceholderBridge placeholders, IntSupplier currentTick) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.placeholders = Objects.requireNonNull(placeholders, "placeholders");
        this.currentTick = Objects.requireNonNull(currentTick, "currentTick");
    }

    @Override
    public Verdict resolve(Player flyer, Player nearby) {
        List<String> bypass = settings.get().pvp().bypassPlaceholders();
        if (bypass.isEmpty() || !placeholders.available()) {
            return Verdict.THREAT;
        }
        int tick = currentTick.getAsInt();
        String[] own = values(flyer, bypass, tick);
        String[] other = values(nearby, bypass, tick);
        return NearbyPlayerChecker.allied(bypass, Arrays.asList(own), Arrays.asList(other))
                ? Verdict.NOT_THREAT : Verdict.THREAT;
    }

    /**
     * Oublie les valeurs mémorisées d'un joueur (déconnexion).
     *
     * @param player joueur
     */
    public void forget(UUID player) {
        cache.remove(player);
    }

    private String[] values(Player p, List<String> inputs, int tick) {
        if (inputs != cacheInputs) {
            cache.clear();
            cacheInputs = inputs;
        }
        UUID id = p.getUniqueId();
        Cached c = cache.get(id);
        if (c != null && isFresh(c.expiresAtTick(), tick)) {
            return c.values();
        }
        String[] out = new String[inputs.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = placeholders.apply(p, inputs.get(i));
        }
        if (cache.size() >= PRUNE_SIZE) {
            cache.values().removeIf(e -> !isFresh(e.expiresAtTick(), tick));
        }
        cache.put(id, new Cached(out, tick + CACHE_TICKS));
        return out;
    }

    /**
     * @param expiresAtTick tick d'expiration
     * @param tick          tick courant
     * @return {@code true} si l'entrée est encore valide (comparaison sûre au débordement)
     */
    static boolean isFresh(int expiresAtTick, int tick) {
        return expiresAtTick - tick > 0;
    }
}
