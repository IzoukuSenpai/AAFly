package com.azurhosts.aafly.timedfly;

import com.azurhosts.aafly.config.Settings;
import java.util.List;
import java.util.Optional;
import org.bukkit.entity.Player;

/**
 * Détermine le groupe timed-fly d'un joueur : premier groupe de la liste (ordre de la configuration)
 * correspondant au groupe primaire LuckPerms (si LuckPerms est présent) ou à la permission
 * {@code aafly.group.<nom>}. Main thread.
 */
public interface GroupResolver {

    /** Préfixe des permissions de groupe. */
    String GROUP_PERMISSION_PREFIX = "aafly.group.";

    /**
     * @param player joueur
     * @param groups groupes configurés, dans l'ordre de priorité
     * @return le premier groupe correspondant, ou vide
     */
    Optional<Settings.TimedFlyGroup> resolve(Player player, List<Settings.TimedFlyGroup> groups);
}
