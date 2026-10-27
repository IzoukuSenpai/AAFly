package com.azurhosts.aafly.api;

import com.azurhosts.aafly.core.TimeChangeResult;
import com.azurhosts.aafly.core.ToggleResult;
import com.azurhosts.aafly.rules.ConditionFactory;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/**
 * API publique d'AAFly, enregistrée dans le {@code ServicesManager} de Bukkit :
 * <pre>{@code
 * FlightApi api = Bukkit.getServicesManager().load(FlightApi.class);
 * }</pre>
 * Toutes les méthodes s'appellent depuis le main thread ; les futures se complètent sur le main thread.
 * Les modifications émettent les événements de {@code com.azurhosts.aafly.api.events} (cause {@code API}).
 */
public interface FlightApi {

    /**
     * @param uuid joueur en ligne
     * @return temps restant en secondes, ou vide si le profil n'est pas chargé
     */
    OptionalLong remainingSeconds(UUID uuid);

    /**
     * @param uuid joueur en ligne
     * @return {@code true} si le vol AAFly est actif
     */
    boolean isFlightEnabled(UUID uuid);

    /**
     * @param player joueur
     * @return {@code true} si le joueur a le vol illimité
     */
    boolean hasUnlimited(Player player);

    /**
     * @param uuid    joueur (en ligne ou hors-ligne connu)
     * @param seconds quantité &gt; 0
     * @return résultat
     */
    CompletableFuture<TimeChangeResult> addTime(UUID uuid, long seconds);

    /**
     * @param uuid    joueur
     * @param seconds quantité &gt; 0
     * @return résultat
     */
    CompletableFuture<TimeChangeResult> removeTime(UUID uuid, long seconds);

    /**
     * @param uuid    joueur
     * @param seconds nouvelle valeur ≥ 0
     * @return résultat
     */
    CompletableFuture<TimeChangeResult> setTime(UUID uuid, long seconds);

    /**
     * @param player joueur en ligne
     * @return résultat
     */
    ToggleResult enableFlight(Player player);

    /**
     * @param player joueur en ligne
     * @return résultat
     */
    ToggleResult disableFlight(Player player);

    /**
     * Enregistre un type de condition personnalisé utilisable dans {@code fly-rules}/{@code decrement-rules}
     * (pris en compte au prochain {@code /aafly reload}).
     *
     * @param type    identifiant {@code ^[a-z0-9_]{1,64}$}
     * @param factory fabrique
     * @throws IllegalArgumentException si le type est invalide ou déjà pris
     */
    void registerCondition(String type, ConditionFactory factory);

    /**
     * @param seconds durée
     * @return durée formatée selon la configuration (chaîne MiniMessage)
     */
    String formatTime(long seconds);
}
