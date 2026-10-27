package com.azurhosts.aafly.rules;

import com.azurhosts.aafly.config.Settings;
import java.util.List;
import java.util.UUID;

/**
 * Moteur de règles (SPEC §5) : tri par priorité croissante, première règle dont toutes les conditions
 * correspondent gagne, aucune règle → ALLOW. Les règles invalides sont journalisées (WARN) et ignorées.
 * Les décisions {@code fly-rules} sont mises en cache par joueur pendant {@code rules.check-interval-seconds}.
 * Main thread uniquement (sauf {@link #reload(Settings)}, appelé lui aussi depuis le main thread).
 */
public interface RuleEngine {

    /**
     * Évalue {@code fly-rules} (avec cache).
     *
     * @param context contexte
     * @return décision
     */
    RuleDecision evaluateFly(RuleContext context);

    /**
     * Évalue {@code fly-rules} (avec cache) avec un contournement déjà connu de l'appelant (ticker), sans
     * revérifier les permissions {@code aafly.bypass.rules}.
     *
     * @param context contexte
     * @param bypass  {@code true} si le joueur contourne les règles (calculé par l'appelant)
     * @return décision (ALLOW par défaut si {@code bypass})
     */
    default RuleDecision evaluateFly(RuleContext context, boolean bypass) {
        return bypass ? RuleDecision.DEFAULT_ALLOW : evaluateFly(context);
    }

    /**
     * Évalue {@code decrement-rules}.
     *
     * @param context contexte
     * @return {@code true} si une règle correspond (le temps ne doit pas être décompté)
     */
    boolean shouldSkipDecrement(RuleContext context);

    /**
     * Évalue {@code decrement-rules} avec un contournement déjà connu de l'appelant (ticker), sans
     * revérifier les permissions {@code aafly.bypass.rules}.
     *
     * @param context contexte
     * @param bypass  {@code true} si le joueur contourne les règles (calculé par l'appelant)
     * @return {@code true} si le temps ne doit pas être décompté
     */
    default boolean shouldSkipDecrement(RuleContext context, boolean bypass) {
        return shouldSkipDecrement(context);
    }

    /**
     * Recharge les règles depuis la configuration ; en cas d'échec partiel, seules les règles
     * valides sont conservées. Vide le cache des décisions.
     *
     * @param settings réglages validés
     */
    void reload(Settings settings);

    /**
     * Oublie la décision en cache d'un joueur (déconnexion, changement de monde…).
     *
     * @param uuid joueur
     */
    void invalidate(UUID uuid);

    /**
     * @return {@code true} si au moins une règle utilise des placeholders PAPI
     */
    boolean usesPlaceholders();

    /**
     * @return règles {@code fly-rules} chargées, triées
     */
    List<Rule> flyRules();

    /**
     * @return règles {@code decrement-rules} chargées, triées
     */
    List<Rule> decrementRules();
}
