package com.azurhosts.aafly.rules;

/**
 * Condition d'une règle. Les implémentations sont immuables, construites une fois au chargement
 * (patterns compilés, nombres parsés) et évaluées sur le main thread.
 * Une entrée {@code input} est résolue via PlaceholderAPI puis comparée : jamais exécutée.
 */
@FunctionalInterface
public interface Condition {

    /**
     * @param context contexte d'évaluation
     * @return {@code true} si la condition est remplie
     */
    boolean test(RuleContext context);

    /**
     * Permet au moteur de n'appeler PlaceholderAPI que si nécessaire.
     *
     * @return {@code true} si la condition résout des placeholders
     */
    default boolean usesPlaceholders() {
        return false;
    }
}
