package com.azurhosts.aafly.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Copie immuable d'un {@link FlightProfile}, transmise au stockage (thread I/O).
 *
 * @param uuid                      identifiant du joueur
 * @param lastKnownName             dernier pseudo connu (validé {@code ^[A-Za-z0-9_]{1,16}$} ou vide)
 * @param remainingSeconds          temps de vol restant (≥ 0)
 * @param flightEnabled             vol voulu par le joueur (réactivé à la reconnexion)
 * @param lastUpdateEpochSeconds    dernière mise à jour (epoch, secondes) pour le décompte hors-ligne
 * @param cooldownUntilEpochSeconds fin de recharge timed-fly (epoch, secondes ; 0 = aucune)
 * @param flySpeed                  vitesse de vol (0.1 à 1.0)
 */
public record FlightProfileSnapshot(
        UUID uuid,
        String lastKnownName,
        long remainingSeconds,
        boolean flightEnabled,
        long lastUpdateEpochSeconds,
        long cooldownUntilEpochSeconds,
        double flySpeed
) {
    public FlightProfileSnapshot {
        Objects.requireNonNull(uuid, "uuid");
        lastKnownName = lastKnownName == null ? "" : lastKnownName;
        remainingSeconds = Math.max(0L, remainingSeconds);
        cooldownUntilEpochSeconds = Math.max(0L, cooldownUntilEpochSeconds);
        flySpeed = FlightProfile.clampSpeed(flySpeed);
    }
}
