package com.azurhosts.aafly.timedfly;

import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.FlightProfile;
import java.util.Optional;
import org.bukkit.entity.Player;

/**
 * Mode timed-fly (SPEC §4) : chaque groupe dispose de {@code fly-time} secondes, rechargées quand la
 * recharge ({@code cooldown}) est écoulée. Quand le mode est actif, add/remove admin et jetons sont refusés.
 * Main thread uniquement.
 */
public interface TimedFlyService {

    /**
     * @return {@code true} si {@code timed-fly.enabled}
     */
    boolean enabled();

    /**
     * @param player joueur
     * @return son groupe timed-fly, ou vide
     */
    Optional<Settings.TimedFlyGroup> groupOf(Player player);

    /**
     * Recharge le temps à {@code fly-time} si la recharge est écoulée (appelé avant une activation et
     * périodiquement par le ticker).
     *
     * @param player          joueur
     * @param profile         son profil
     * @param nowEpochSeconds instant présent
     * @return {@code true} si le temps a été rechargé
     */
    boolean refillIfReady(Player player, FlightProfile profile, long nowEpochSeconds);

    /**
     * Appelé quand le vol se coupe (expiration ou désactivation) : démarre la recharge et, si
     * {@code reset-after-disable}, remet le temps à 0.
     *
     * @param player          joueur
     * @param profile         son profil
     * @param nowEpochSeconds instant présent
     */
    void onFlightStopped(Player player, FlightProfile profile, long nowEpochSeconds);

    /**
     * Décompte hors-ligne de la recharge à la connexion (si {@code decrement-cooldown-offline}).
     *
     * @param profile         profil chargé
     * @param nowEpochSeconds instant présent
     */
    void applyOffline(FlightProfile profile, long nowEpochSeconds);

    /**
     * @param profile         profil
     * @param nowEpochSeconds instant présent
     * @return secondes de recharge restantes (0 si aucune)
     */
    long cooldownRemaining(FlightProfile profile, long nowEpochSeconds);

    /**
     * @param settings réglages validés
     */
    void reload(Settings settings);
}
