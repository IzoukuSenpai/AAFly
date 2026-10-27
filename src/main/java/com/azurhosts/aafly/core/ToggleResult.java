package com.azurhosts.aafly.core;

/** Résultat d'une demande d'activation/désactivation du vol. */
public enum ToggleResult {
    /** Le vol a été activé. */
    ENABLED,
    /** Le vol a été désactivé. */
    DISABLED,
    /** Le vol était déjà dans l'état demandé. */
    ALREADY,
    /** Activation refusée : plus de temps de vol (et pas illimité). */
    NO_TIME,
    /** Activation refusée par une règle {@code fly-rules} (DENY). */
    DENIED_BY_RULE,
    /** Activation refusée : ennemi à proximité (section {@code pvp}). */
    DENIED_NEARBY,
    /** Activation refusée : recharge timed-fly en cours. */
    TIMED_FLY_COOLDOWN,
    /** Annulé par un listener de {@code FlightToggleEvent}. */
    CANCELLED,
    /** Profil pas encore chargé depuis le stockage. */
    NOT_LOADED;

    /**
     * @return {@code true} si l'état du vol a effectivement changé
     */
    public boolean changed() {
        return this == ENABLED || this == DISABLED;
    }
}
