package com.azurhosts.aafly.core;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Résultat d'une opération sur le temps de vol (add/remove/set/reset).
 *
 * @param status     issue de l'opération
 * @param uuid       joueur concerné
 * @param oldSeconds temps avant l'opération (0 si inconnu)
 * @param newSeconds temps après l'opération (égal à {@code oldSeconds} en cas d'échec)
 * @param profile    profil modifié (en ligne ou chargé hors-ligne), {@code null} si inconnu
 */
public record TimeChangeResult(Status status, UUID uuid, long oldSeconds, long newSeconds,
                               @Nullable FlightProfile profile) {

    /** Issue d'une opération sur le temps. */
    public enum Status {
        /** Opération appliquée. */
        OK,
        /** Opération appliquée mais bornée par {@code limits.max-seconds} ou par 0. */
        CLAMPED,
        /** Joueur jamais vu (aucun profil en base). */
        UNKNOWN_PLAYER,
        /** Annulé par un listener de {@code FlightTimeChangeEvent}. */
        CANCELLED,
        /** Refusé : le mode timed-fly gère le temps. */
        TIMED_FLY_LOCKED,
        /** Durée invalide (≤ 0 ou hors bornes). */
        INVALID_AMOUNT,
        /** Erreur d'accès au stockage. */
        STORAGE_ERROR
    }

    /**
     * @return {@code true} si la valeur a été appliquée ({@link Status#OK} ou {@link Status#CLAMPED})
     */
    public boolean success() {
        return status == Status.OK || status == Status.CLAMPED;
    }

    /**
     * Fabrique un résultat d'échec.
     *
     * @param status cause de l'échec
     * @param uuid   joueur
     * @return résultat sans profil
     */
    public static TimeChangeResult failure(Status status, UUID uuid) {
        return new TimeChangeResult(status, uuid, 0L, 0L, null);
    }
}
