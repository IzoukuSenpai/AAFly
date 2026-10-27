package com.azurhosts.aafly.core;

/**
 * Origine d'un changement d'état du vol (activation ou désactivation).
 * Utilisée par {@link FlightService} et transmise dans {@code FlightToggleEvent}.
 */
public enum DisableCause {
    /** Le joueur lui-même ({@code /fly}). */
    PLAYER,
    /** Un administrateur ({@code /aafly activate|deactivate|toggle}). */
    ADMIN,
    /** Temps de vol écoulé. */
    EXPIRED,
    /** Une règle {@code fly-rules} a interdit (ou de nouveau autorisé) le vol. */
    RULE,
    /** Joueur ennemi à proximité (section {@code pvp}). */
    NEARBY,
    /** Changement de monde. */
    WORLD_CHANGE,
    /**
     * Changement de mode de jeu. Cause <b>réservée à l'API</b> : AAFly ne l'émet pas lui-même (un changement
     * de mode ne coupe jamais le vol AAFly, il est réappliqué) ; un plugin tiers qui désactive le vol avec
     * cette cause déclenche le message {@code fly.gamemode-change} et les actions {@code on-disable}.
     */
    GAMEMODE,
    /**
     * Mode timed-fly. Cause <b>réservée à l'API</b> : AAFly ne l'émet pas lui-même (l'expiration du temps
     * de groupe utilise {@link #EXPIRED}, la recharge n'est pas un changement d'état du vol).
     */
    TIMED_FLY,
    /** Plugin tiers via {@code FlightApi}. */
    API;

    /**
     * Indique si cette cause est une suspension automatique, levée d'elle-même quand la condition
     * disparaît (si {@code flight.auto-reactivate-after-rule}).
     *
     * @return {@code true} pour {@link #RULE} et {@link #NEARBY}
     */
    public boolean isSuspension() {
        return this == RULE || this == NEARBY;
    }
}
