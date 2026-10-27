package com.azurhosts.aafly.action;

import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import java.util.Optional;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Services dont les actions ont besoin à l'exécution. Séparé de l'analyse pour que
 * {@link DefaultActionParser} reste utilisable sans serveur (tests) : l'analyse n'appelle jamais
 * ces méthodes. Implémentation serveur : {@link BukkitActionRuntime}.
 */
public interface ActionRuntime {

    /**
     * @return rendu MiniMessage (tag {@code <prefix>} inclus)
     */
    Lang lang();

    /**
     * @return pont PlaceholderAPI
     */
    PlaceholderBridge placeholders();

    /**
     * @return logger du plugin
     */
    Logger logger();

    /**
     * Exécute une commande par la console (contenu issu de la configuration uniquement).
     *
     * @param command commande sans {@code /}
     */
    void dispatchConsole(String command);

    /**
     * @return {@code true} si l'appel a lieu sur le main thread
     */
    boolean isPrimaryThread();

    /**
     * Planifie une tâche sur le main thread (ignorée si le plugin est désactivé).
     *
     * @param task tâche
     */
    void runOnMain(Runnable task);

    /**
     * Résout un nom de son de style enum ({@code BLOCK_NOTE_BLOCK_PLING}) en clé de registre.
     *
     * @param enumName nom en majuscules
     * @return clé du son, ou vide si inconnu
     */
    Optional<Key> resolveSoundName(String enumName);

    /**
     * Temps restant formaté du joueur (utilisé si {@code %remaining%} n'est pas fourni par l'appelant).
     *
     * @param player joueur
     * @return texte formaté, ou {@code null} si indisponible
     */
    @Nullable String remaining(Player player);

    /**
     * Journalise un avertissement une seule fois par clé (évite l'inondation des logs à chaque tick).
     *
     * @param key     clé de déduplication
     * @param message message
     */
    void warnOnce(String key, String message);
}
