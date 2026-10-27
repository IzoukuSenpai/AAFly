package com.azurhosts.aafly.core;

import org.bukkit.entity.Player;

/**
 * Atterrissage sécurisé quand le vol est coupé : téléporte sur le bloc solide le plus proche sous
 * le joueur (orientation conservée) et pose une protection contre les dégâts de chute qui expire après
 * {@link #FALL_PROTECTION_MILLIS}. Main thread uniquement.
 */
public interface SafeLanding {

    /** Durée de validité de la protection anti-chute. */
    long FALL_PROTECTION_MILLIS = 5_000L;

    /**
     * Fait atterrir le joueur s'il est en l'air (aucun effet s'il est au sol ou en creative/spectator).
     *
     * @param player joueur
     * @return {@code true} si une téléportation a eu lieu ou si une protection a été posée
     */
    boolean land(Player player);

    /**
     * Consomme la protection anti-chute (appelé par le FallDamageListener).
     *
     * @param player joueur subissant des dégâts de chute
     * @return {@code true} si une protection valide existait (les dégâts doivent être annulés)
     */
    boolean consumeFallProtection(Player player);

    /**
     * Oublie l'état d'un joueur (déconnexion).
     *
     * @param player joueur
     */
    void forget(Player player);
}
