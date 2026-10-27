package com.azurhosts.aafly.api;

import org.bukkit.entity.Player;

/**
 * Résolveur de menace injectable (section {@code pvp} d'AAFly) : décide si un joueur proche est une menace
 * pour un joueur en vol (ex. intégration d'un plugin de factions, de lands ou de combat).
 *
 * <p>Enregistrement : {@link AAFlyApi#registerThreatResolver}. Appelé sur le main thread, à la fréquence
 * {@code pvp.check-interval-seconds} et lors d'une demande d'activation, pour chaque joueur proche déjà
 * filtré par AAFly (rayon, spectateurs, NPC, invisibles). Doit être rapide et ne jamais bloquer.
 *
 * <p>Combinaison (choix sûr) : si au moins un résolveur répond {@link Verdict#THREAT}, le joueur est une
 * menace ; sinon, si au moins un répond {@link Verdict#NOT_THREAT}, il est ignoré ; si tous s'abstiennent,
 * la règle intégrée d'AAFly ({@code pvp.bypass-placeholders}) s'applique. Une exception levée par un
 * résolveur vaut {@link Verdict#ABSTAIN} (et est journalisée). Les résolveurs d'un plugin désactivé sont
 * retirés automatiquement.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
@FunctionalInterface
public interface ThreatResolver {

    /** Avis d'un résolveur sur un joueur proche. */
    enum Verdict {
        /** Le joueur proche est une menace : le vol doit être coupé ou refusé. */
        THREAT,
        /** Le joueur proche n'est pas une menace (allié, même faction…), sauf si un autre résolveur dit THREAT. */
        NOT_THREAT,
        /** Pas d'avis : laisser décider les autres résolveurs ou la règle intégrée. */
        ABSTAIN
    }

    /**
     * @param flyer  joueur en vol (ou qui demande le vol)
     * @param nearby joueur proche, distinct de {@code flyer}
     * @return avis, jamais {@code null} ({@code null} est traité comme {@link Verdict#ABSTAIN})
     */
    Verdict resolve(Player flyer, Player nearby);
}
