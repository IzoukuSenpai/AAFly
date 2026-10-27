package com.azurhosts.aafly.api;

/**
 * Condition personnalisée utilisable dans {@code fly-rules} / {@code decrement-rules}, produite par une
 * {@link ConditionFactory}. Immuable, construite une fois au chargement des règles, évaluée sur le main thread
 * (potentiellement chaque seconde pour chaque joueur en vol) : doit être rapide et ne jamais bloquer.
 *
 * <p>Une exception levée par {@link #test} est journalisée et vaut {@code false} (condition non remplie).
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
@FunctionalInterface
public interface FlightCondition {

    /**
     * @param context contexte d'évaluation
     * @return {@code true} si la condition est remplie
     */
    boolean test(ConditionContext context);

    /**
     * Permet à AAFly de n'appeler PlaceholderAPI que si nécessaire.
     *
     * @return {@code true} si la condition appelle {@link ConditionContext#resolve}
     */
    default boolean usesPlaceholders() {
        return false;
    }
}
