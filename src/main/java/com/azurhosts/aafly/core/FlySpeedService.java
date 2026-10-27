package com.azurhosts.aafly.core;

import org.bukkit.entity.Player;

/**
 * Vitesse de vol : niveaux 1 à 10 mappés sur 0.1 à 1.0, plafond par permission {@code aafly.speed.<n>}.
 * Main thread uniquement.
 */
public interface FlySpeedService {

    /** Niveau minimal. */
    int MIN_LEVEL = 1;
    /** Niveau maximal. */
    int MAX_LEVEL = 10;
    /** Permission de base de {@code /flyspeed}. */
    String PERM_SPEED = "aafly.speed";

    /**
     * Plus haut niveau autorisé : maximum des {@code aafly.speed.<n>} possédées (1..10) ;
     * avec seulement {@code aafly.speed} : {@link #MAX_LEVEL}.
     *
     * @param player joueur
     * @return niveau maximal autorisé (≥ 1)
     */
    int maxLevel(Player player);

    /**
     * Fixe le niveau (vérifie bornes et plafond), le persiste dans le profil et l'applique au joueur.
     *
     * @param player joueur
     * @param level  niveau demandé
     * @return {@code true} si appliqué
     */
    boolean setLevel(Player player, int level);

    /**
     * Applique la vitesse stockée dans le profil au joueur ({@code Player#setFlySpeed}).
     *
     * @param player  joueur
     * @param profile son profil
     */
    void apply(Player player, FlightProfile profile);

    /**
     * @param level niveau 1..10 (borné)
     * @return vitesse 0.1..1.0
     */
    static double levelToSpeed(int level) {
        int l = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
        return l / 10.0D;
    }

    /**
     * @param speed vitesse (bornée à 0.1..1.0)
     * @return niveau 1..10 le plus proche
     */
    static int speedToLevel(double speed) {
        double s = FlightProfile.clampSpeed(speed);
        return (int) Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, Math.round(s * 10.0D)));
    }
}
