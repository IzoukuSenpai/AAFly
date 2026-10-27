package com.azurhosts.aafly.action;

import com.azurhosts.aafly.config.MessagesConfig;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Déclenche les actions {@code commands-time-remaining} quand le temps restant atteint un seuil,
 * une seule fois par passage (si le temps remonte au-dessus du seuil, il peut se redéclencher).
 * Main thread uniquement.
 */
public interface TimeThresholdNotifier {

    /**
     * Appelé par le ticker après chaque décompte.
     *
     * @param player           joueur en vol
     * @param remainingSeconds temps restant après décompte
     */
    void onTick(Player player, long remainingSeconds);

    /**
     * Oublie l'état d'un joueur (déconnexion, ajout de temps).
     *
     * @param uuid joueur
     */
    void reset(UUID uuid);

    /**
     * Recharge les seuils (les actions sont ré-analysées).
     *
     * @param messages messages validés
     */
    void reload(MessagesConfig messages);
}
