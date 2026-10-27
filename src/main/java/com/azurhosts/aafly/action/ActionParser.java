package com.azurhosts.aafly.action;

import java.util.List;

/**
 * Analyse des actions {@code "[type] contenu"} (SPEC §6). L'analyse a lieu au chargement de la
 * configuration ; les actions produites sont immuables.
 */
public interface ActionParser {

    /**
     * @param raw chaîne brute, ex. {@code "[title] <red>Vol terminé;<gray>Temps écoulé"}
     * @return action prête à exécuter
     * @throws InvalidActionException si le format, le type ou le contenu est invalide
     *                                (ex. {@code [player]} alors que {@code actions.allow-player-commands: false})
     */
    Action parse(String raw) throws InvalidActionException;

    /**
     * Analyse une liste ; chaque entrée invalide est journalisée (WARN avec la raison) et ignorée.
     *
     * @param raws chaînes brutes (peut être vide)
     * @param context description pour les logs (ex. {@code "fly-rules.no_combat.actions"})
     * @return actions valides, dans l'ordre
     */
    List<Action> parseAll(List<String> raws, String context);
}
