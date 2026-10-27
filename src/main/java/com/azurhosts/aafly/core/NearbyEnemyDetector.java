package com.azurhosts.aafly.core;

import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import com.azurhosts.aafly.rules.NearbyPlayerChecker;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Détection d'un joueur « ennemi » à proximité (section {@code pvp}, SPEC §4 « Nearby / PVP »).
 *
 * <p>Un joueur proche est considéré comme allié s'il partage, pour <b>tous</b> les placeholders de
 * {@code pvp.bypass-placeholders}, la même valeur non vide et effectivement résolue (même land, même faction…).
 * Une valeur vide ou non résolue (placeholder renvoyé tel quel) ne rend jamais deux joueurs alliés :
 * deux joueurs « sans faction » restent ennemis. Les spectateurs, les NPC (métadonnée {@code NPC}), les entités
 * invalides et les joueurs invisibles pour le joueur testé sont ignorés. Main thread uniquement ; coût O(joueurs dans le rayon).
 *
 * <p>Les valeurs résolues sont mémorisées par joueur pour la durée d'un tick serveur : chaque joueur est
 * résolu via PlaceholderAPI au plus une fois par tick, quel que soit le nombre de joueurs en vol autour de lui.
 */
public final class NearbyEnemyDetector {

    private final Supplier<Settings> settings;
    private final PlaceholderBridge placeholders;
    /** Valeurs bypass résolues par joueur, valables pour {@link #memoTick} et {@link #memoInputs}. */
    private final Map<UUID, String[]> tickMemo = new HashMap<>();
    private int memoTick = Integer.MIN_VALUE;
    private List<String> memoInputs = List.of();

    /**
     * @param settings     réglages courants (relus à chaque appel : compatible {@code /aafly reload})
     * @param placeholders pont PlaceholderAPI
     */
    public NearbyEnemyDetector(Supplier<Settings> settings, PlaceholderBridge placeholders) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.placeholders = Objects.requireNonNull(placeholders, "placeholders");
    }

    /**
     * @param player joueur en vol
     * @return {@code true} si au moins un ennemi se trouve dans le rayon {@code pvp.radius}
     */
    public boolean hasEnemyNearby(Player player) {
        Settings.PvpSection pvp = settings.get().pvp();
        if (!pvp.enabled()) {
            return false;
        }
        double radius = Math.max(1, Math.min(64, pvp.radius()));
        Location loc = player.getLocation();
        List<String> bypass = pvp.bypassPlaceholders();
        String[] own = null;
        for (Player other : loc.getWorld().getNearbyPlayers(loc, radius)) {
            if (other == player || other.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            // NPC (métadonnée « NPC », ex. Citizens) et entités invalides ignorés : évite aussi des appels PAPI inutiles.
            if (!other.isValid() || other.hasMetadata("NPC") || other.getGameMode() == GameMode.SPECTATOR
                    || !player.canSee(other)) {
                continue;
            }
            if (bypass.isEmpty() || !placeholders.available()) {
                return true;
            }
            if (own == null) {
                own = resolveAll(player, bypass);
            }
            if (!isAlly(own, resolveAll(other, bypass), bypass)) {
                return true;
            }
        }
        return false;
    }

    private String[] resolveAll(Player p, List<String> inputs) {
        int tick = Bukkit.getCurrentTick();
        if (tick != memoTick || inputs != memoInputs) {
            tickMemo.clear();
            memoTick = tick;
            memoInputs = inputs;
        }
        return tickMemo.computeIfAbsent(p.getUniqueId(), id -> resolve(p, inputs));
    }

    private String[] resolve(Player p, List<String> inputs) {
        String[] out = new String[inputs.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = placeholders.apply(p, inputs.get(i));
        }
        return out;
    }

    /** Règle d'alliance unique, partagée avec {@link NearbyPlayerChecker#allied}. */
    private static boolean isAlly(String[] a, String[] b, List<String> raw) {
        return NearbyPlayerChecker.allied(raw, Arrays.asList(a), Arrays.asList(b));
    }
}
