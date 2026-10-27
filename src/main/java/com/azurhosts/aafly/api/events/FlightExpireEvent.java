package com.azurhosts.aafly.api.events;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Émis (main thread) quand le temps de vol d'un joueur atteint 0, juste avant la désactivation
 * de cause {@code EXPIRED}. Non annulable : pour prolonger le vol, ajoutez du temps via l'API.
 */
public final class FlightExpireEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * @param player joueur dont le temps est écoulé
     */
    public FlightExpireEvent(Player player) {
        super(Objects.requireNonNull(player, "player"));
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * @return liste des handlers (requis par Bukkit)
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
