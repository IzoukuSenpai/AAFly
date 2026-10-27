package com.azurhosts.aafly.api.events;

import com.azurhosts.aafly.api.DisableCause;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Émis (main thread) avant l'activation ou la désactivation du vol AAFly d'un joueur.
 * Annulable, sauf pour une désactivation de cause {@link DisableCause#EXPIRED}
 * (l'annulation est alors ignorée : le temps est épuisé).
 */
public final class FlightToggleEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean enabling;
    private final DisableCause cause;
    private boolean cancelled;

    /**
     * @param player   joueur
     * @param enabling {@code true} pour une activation, {@code false} pour une désactivation
     * @param cause    origine
     */
    public FlightToggleEvent(Player player, boolean enabling, DisableCause cause) {
        super(Objects.requireNonNull(player, "player"));
        this.enabling = enabling;
        this.cause = Objects.requireNonNull(cause, "cause");
    }

    /**
     * @return {@code true} pour une activation
     */
    public boolean isEnabling() {
        return enabling;
    }

    /**
     * @return origine du changement
     */
    public DisableCause getCause() {
        return cause;
    }

    /**
     * @return {@code true} si l'annulation est prise en compte pour cet événement
     */
    public boolean isCancellableCause() {
        return enabling || cause != DisableCause.EXPIRED;
    }

    @Override
    public boolean isCancelled() {
        return cancelled && isCancellableCause();
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
