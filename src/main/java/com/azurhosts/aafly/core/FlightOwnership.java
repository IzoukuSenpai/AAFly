package com.azurhosts.aafly.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Propriété du vol (SPEC-phase2 B3) : mémorise, <b>en mémoire uniquement</b>, les joueurs dont le vol physique
 * ({@code allowFlight}) a été accordé par AAFly. AAFly ne retire que le vol qu'il possède : s'il ne le possède
 * pas (vol accordé par AAItems, un autre plugin, le mode de jeu…), il ne touche ni {@code allowFlight} ni
 * {@code flying}.
 *
 * <p>Règles :
 * <ul>
 *   <li>AAFly devient propriétaire quand il pose lui-même {@code allowFlight=true} ({@link #claim}) — y compris si
 *       un autre plugin l'avait déjà posé : AAFly ne cherche pas à deviner qui l'avait accordé ;</li>
 *   <li>il perd la propriété quand il retire le vol ({@link #release}) ou quand il constate qu'un tiers a retiré
 *       {@code allowFlight} ({@link #release} appelé par le ticker) ;</li>
 *   <li>déconnexion : {@link #forget}.</li>
 * </ul>
 *
 * <p>Ce registre en mémoire est doublé par un marqueur persistant posé par le service de vol
 * ({@code DefaultFlightService#OWNS_FLIGHT_KEY}, dans le playerdata avec {@code mayfly}) : seul ce marqueur permet,
 * à la reconnexion, de reprendre la propriété d'un vol vanilla restauré (et de le retirer s'il n'est plus voulu).
 *
 * <p>Classe pure (aucun appel serveur), confinée au main thread. Coût O(1) par opération.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class FlightOwnership {

    private final Set<UUID> owned = new HashSet<>();

    /**
     * AAFly vient d'accorder le vol à ce joueur.
     *
     * @param player joueur
     * @return {@code true} si AAFly ne le possédait pas encore
     */
    public boolean claim(UUID player) {
        return owned.add(Objects.requireNonNull(player, "player"));
    }

    /**
     * AAFly cède la propriété (vol retiré par AAFly, ou retiré par un tiers).
     *
     * @param player joueur
     * @return {@code true} si AAFly possédait le vol (et seulement dans ce cas l'appelant peut y toucher)
     */
    public boolean release(UUID player) {
        return owned.remove(Objects.requireNonNull(player, "player"));
    }

    /**
     * @param player joueur
     * @return {@code true} si le vol physique de ce joueur a été accordé par AAFly
     */
    public boolean owns(UUID player) {
        return owned.contains(player);
    }

    /**
     * Oublie un joueur (déconnexion) sans rien modifier de son état physique.
     *
     * @param player joueur
     */
    public void forget(UUID player) {
        owned.remove(player);
    }

    /**
     * @return copie des joueurs dont AAFly possède le vol (itération sûre pendant des {@link #release})
     */
    public List<UUID> snapshot() {
        return List.copyOf(owned);
    }

    /**
     * @return nombre de vols possédés
     */
    public int size() {
        return owned.size();
    }

    /**
     * Décision de coexistence pour un retrait de vol : AAFly ne touche à {@code allowFlight} / {@code flying} que
     * s'il possède le vol, et jamais en creative/spectator (vol natif du mode de jeu).
     *
     * @param ownedByAAFly    AAFly possède le vol
     * @param nativeGameMode  joueur en creative ou spectator
     * @return {@code true} si AAFly peut retirer physiquement le vol
     */
    public static boolean mayRevoke(boolean ownedByAAFly, boolean nativeGameMode) {
        return ownedByAAFly && !nativeGameMode;
    }

    /**
     * Le vol possédé par AAFly a-t-il été retiré par un tiers ? (vu par le ticker : propriétaire mais
     * {@code allowFlight} faux hors creative/spectator)
     *
     * @param ownedByAAFly   AAFly possède le vol
     * @param allowFlight    {@code Player#getAllowFlight()}
     * @param nativeGameMode joueur en creative ou spectator
     * @return {@code true} si la propriété doit être cédée
     */
    public static boolean lostToThirdParty(boolean ownedByAAFly, boolean allowFlight, boolean nativeGameMode) {
        return ownedByAAFly && !allowFlight && !nativeGameMode;
    }
}
