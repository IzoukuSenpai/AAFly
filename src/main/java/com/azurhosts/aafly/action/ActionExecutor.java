package com.azurhosts.aafly.action;

import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;

/**
 * Exécution d'une liste d'actions sur le main thread. Une action qui lève une exception est
 * journalisée et n'interrompt pas les suivantes.
 */
public interface ActionExecutor {

    /**
     * @param player  joueur cible (en ligne)
     * @param actions actions à exécuter dans l'ordre
     * @param vars    variables supplémentaires ; {@code player} et {@code uuid} sont ajoutées automatiquement
     */
    void run(Player player, List<Action> actions, Map<String, String> vars);

    /**
     * Exécute une liste d'actions nommée dans {@code messages.yml} ({@code on-enable}, {@code on-disable},
     * {@code on-expire}, {@code not-allowed-here}).
     *
     * @param player joueur cible
     * @param key    clé de la liste
     * @param vars   variables supplémentaires
     */
    void runNamed(Player player, String key, Map<String, String> vars);
}
