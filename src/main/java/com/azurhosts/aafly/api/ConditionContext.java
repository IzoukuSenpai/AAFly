package com.azurhosts.aafly.api;

import org.bukkit.entity.Player;

/**
 * Contexte d'évaluation d'une {@link FlightCondition} personnalisée (main thread, durée de vie d'une seule
 * évaluation : ne pas le conserver).
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public interface ConditionContext {

    /**
     * @return joueur évalué
     */
    Player player();

    /**
     * Résout les placeholders PlaceholderAPI d'un texte pour le joueur évalué (mémorisé pendant l'évaluation).
     * Sans PlaceholderAPI, le texte est renvoyé tel quel. Le résultat est une simple chaîne : ne jamais
     * l'exécuter ni l'insérer dans une commande.
     *
     * @param input texte brut (peut être {@code null}, traité comme vide)
     * @return texte résolu, jamais {@code null}
     */
    String resolve(String input);
}
