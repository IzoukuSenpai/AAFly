package com.azurhosts.aafly.api.events;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/**
 * Émis (main thread) avant une modification ponctuelle du temps de vol (ajout, retrait, réglage, don,
 * jeton…). <b>N'est pas émis pour le décompte seconde par seconde</b> (performance).
 * Annulable ; la nouvelle valeur peut être modifiée (bornée à ≥ 0, puis plafonnée par AAFly).
 */
public final class FlightTimeChangeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Nature de la modification. */
    public enum Reason {
        /** {@code /aafly add} ou {@code addall}. */
        ADD,
        /** {@code /aafly remove} ou {@code removeall}. */
        REMOVE,
        /** {@code /aafly set}. */
        SET,
        /** {@code /aafly reset}. */
        RESET,
        /** {@code /fly give} (débit du donneur ou crédit du receveur). */
        GIVE,
        /** Création ou encaissement d'un jeton. */
        TOKEN,
        /** Recharge timed-fly. */
        TIMED_FLY,
        /** Décompte hors-ligne appliqué à la connexion. */
        OFFLINE_DECREMENT,
        /** Plugin tiers via {@code FlightApi}. */
        API
    }

    private final UUID uuid;
    private final @Nullable Player player;
    private final @Nullable CommandSender actor;
    private final Reason reason;
    private final long oldSeconds;
    private long newSeconds;
    private boolean cancelled;

    /**
     * @param uuid       joueur concerné
     * @param player     le joueur s'il est en ligne, sinon {@code null}
     * @param actor      auteur de la modification, ou {@code null}
     * @param reason     nature de la modification
     * @param oldSeconds valeur actuelle
     * @param newSeconds valeur proposée
     */
    public FlightTimeChangeEvent(UUID uuid, @Nullable Player player, @Nullable CommandSender actor, Reason reason,
                                 long oldSeconds, long newSeconds) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.player = player;
        this.actor = actor;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.oldSeconds = Math.max(0L, oldSeconds);
        this.newSeconds = Math.max(0L, newSeconds);
    }

    public UUID getUniqueId() {
        return uuid;
    }

    public @Nullable Player getPlayer() {
        return player;
    }

    public @Nullable CommandSender getActor() {
        return actor;
    }

    public Reason getReason() {
        return reason;
    }

    public long getOldSeconds() {
        return oldSeconds;
    }

    public long getNewSeconds() {
        return newSeconds;
    }

    /**
     * @param newSeconds nouvelle valeur (une valeur négative devient 0)
     */
    public void setNewSeconds(long newSeconds) {
        this.newSeconds = Math.max(0L, newSeconds);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
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
