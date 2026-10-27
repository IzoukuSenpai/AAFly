package com.azurhosts.aafly.action;

import java.util.Map;
import org.bukkit.entity.Player;

/**
 * Action configurée ({@code "[type] contenu"}, SPEC §6), immuable, exécutée sur le main thread.
 *
 * <p>Substitutions appliquées par l'implémentation, dans cet ordre : {@code %player%} (pseudo validé
 * {@code ^[A-Za-z0-9_]{1,16}$}, sinon action ignorée + WARN), {@code %uuid%}, {@code %remaining%} (durée formatée),
 * {@code %remaining_seconds%} (entier),
 * puis PlaceholderAPI. Aucune autre interpolation. Le contenu des commandes provient uniquement
 * de la configuration.
 */
@FunctionalInterface
public interface Action {

    /** Variable : pseudo du joueur. */
    String VAR_PLAYER = "player";
    /** Variable : UUID du joueur. */
    String VAR_UUID = "uuid";
    /** Variable : temps restant formaté. */
    String VAR_REMAINING = "remaining";
    /** Variable : temps restant en secondes (entier, sûr dans une commande). */
    String VAR_REMAINING_SECONDS = "remaining_seconds";

    /**
     * @param player joueur cible
     * @param vars   variables disponibles (clés {@link #VAR_PLAYER}, {@link #VAR_UUID}, {@link #VAR_REMAINING}…)
     */
    void execute(Player player, Map<String, String> vars);

    /**
     * @return origine de l'action pour les journaux (ex. {@code "fly-rules.no_combat.actions"}), vide si inconnue
     */
    default String origin() {
        return "";
    }
}
