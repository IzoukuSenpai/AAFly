package com.azurhosts.aafly.core;

/**
 * Tâche synchrone unique (toutes les 20 ticks) qui décompte le temps, applique les règles,
 * les seuils, l'action bar et l'expiration (SPEC §4 « FlightTicker »). Coût O(joueurs en vol).
 */
public interface FlightTicker {

    /** Démarre la tâche (idempotent). */
    void start();

    /** Arrête la tâche (idempotent). */
    void stop();

    /**
     * @return {@code true} si la tâche tourne
     */
    boolean running();

    /**
     * @return statistiques pour {@code /aafly about}
     */
    Stats stats();

    /**
     * Statistiques du ticker.
     *
     * @param ticks            nombre d'exécutions depuis le démarrage
     * @param lastTickNanos    durée de la dernière exécution
     * @param averageTickNanos durée moyenne (moyenne glissante)
     * @param trackedPlayers   joueurs traités lors de la dernière exécution
     */
    record Stats(long ticks, long lastTickNanos, long averageTickNanos, int trackedPlayers) {
        /** Statistiques vides. */
        public static final Stats EMPTY = new Stats(0L, 0L, 0L, 0);
    }
}
