package com.azurhosts.aafly.core;

import com.azurhosts.aafly.api.ThreatResolver;
import com.azurhosts.aafly.api.ThreatResolver.Verdict;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Détection d'une menace à proximité (section {@code pvp}, SPEC §4 « Nearby / PVP », SPEC-phase2 A6).
 *
 * <p>Pour chaque joueur dans le rayon {@code pvp.radius} — hors spectateurs, NPC (métadonnée {@code NPC}),
 * entités invalides et joueurs invisibles pour le joueur testé — l'avis est demandé d'abord aux résolveurs
 * tiers ({@link ThreatResolverRegistry}, injectés par l'API) puis, s'ils s'abstiennent tous, au résolveur par
 * défaut ({@link BuiltinThreatResolver} : règle {@code pvp.bypass-placeholders}). Le premier joueur jugé
 * menaçant suffit.
 *
 * <p>Aucun travail sur {@code PlayerMoveEvent} : appelé par le ticker (throttlé par
 * {@code pvp.check-interval-seconds}), à l'activation et sur {@code PlayerToggleFlightEvent}. Main thread
 * uniquement ; coût O(joueurs dans le rayon), placeholders mis en cache 2 s par le résolveur par défaut.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class NearbyEnemyDetector {

    private final Supplier<Settings> settings;
    private final ThreatResolverRegistry registry;
    private final ThreatResolver fallback;

    /**
     * Détecteur avec le résolveur par défaut et un registre tiers vide (compatibilité de câblage).
     *
     * @param settings     réglages courants (relus à chaque appel : compatible {@code /aafly reload})
     * @param placeholders pont PlaceholderAPI
     */
    public NearbyEnemyDetector(Supplier<Settings> settings, PlaceholderBridge placeholders) {
        this(settings, new ThreatResolverRegistry(Logger.getLogger("AAFly")),
                new BuiltinThreatResolver(settings, placeholders));
    }

    /**
     * @param settings réglages courants
     * @param registry résolveurs tiers (API)
     * @param fallback résolveur par défaut, consulté quand tous les résolveurs tiers s'abstiennent
     */
    public NearbyEnemyDetector(Supplier<Settings> settings, ThreatResolverRegistry registry, ThreatResolver fallback) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /**
     * @return registre des résolveurs tiers (enregistrement via {@code AAFlyApi#registerThreatResolver})
     */
    public ThreatResolverRegistry registry() {
        return registry;
    }

    /**
     * @return {@code true} si la détection est active ({@code pvp.enabled})
     */
    public boolean enabled() {
        return settings.get().pvp().enabled();
    }

    /**
     * @param player joueur en vol (ou qui demande le vol)
     * @return {@code true} si au moins une menace se trouve dans le rayon {@code pvp.radius}
     */
    public boolean hasEnemyNearby(Player player) {
        Settings.PvpSection pvp = settings.get().pvp();
        if (!pvp.enabled()) {
            return false;
        }
        double radius = Math.max(1, Math.min(64, pvp.radius()));
        Location loc = player.getLocation();
        UUID self = player.getUniqueId();
        for (Player other : loc.getWorld().getNearbyPlayers(loc, radius)) {
            if (other == player || other.getUniqueId().equals(self)) {
                continue;
            }
            // NPC (métadonnée « NPC », ex. Citizens) et entités invalides ignorés : évite aussi des appels PAPI inutiles.
            if (!other.isValid() || other.hasMetadata("NPC") || other.getGameMode() == GameMode.SPECTATOR
                    || !player.canSee(other)) {
                continue;
            }
            if (isThreat(player, other)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Avis final sur un joueur proche : résolveurs tiers, puis résolveur par défaut s'ils s'abstiennent.
     * Une erreur du résolveur par défaut vaut menace (choix sûr).
     */
    private boolean isThreat(Player flyer, Player other) {
        Verdict external = registry.resolve(flyer, other);
        if (external != Verdict.ABSTAIN) {
            return external == Verdict.THREAT;
        }
        Verdict builtin;
        try {
            builtin = fallback.resolve(flyer, other);
        } catch (RuntimeException e) {
            return true;
        }
        return decide(external, builtin);
    }

    /**
     * Décision pure : un avis tiers tranche ; sinon l'avis par défaut s'applique, et toute abstention ou
     * absence d'avis par défaut vaut menace (jamais d'alliance implicite).
     *
     * @param external avis combiné des résolveurs tiers
     * @param builtin  avis du résolveur par défaut (peut être {@code null})
     * @return {@code true} si le joueur proche est une menace
     */
    static boolean decide(Verdict external, Verdict builtin) {
        if (external != Verdict.ABSTAIN) {
            return external == Verdict.THREAT;
        }
        return builtin != Verdict.NOT_THREAT;
    }
}
