package com.azurhosts.aafly.api;

import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * API publique stable d'AAFly (vol temporisé par AzurHOSTS).
 *
 * <p>Obtention :
 * <pre>{@code
 * AAFlyApi api = AAFlyProvider.get();                                    // IllegalStateException si absent
 * AAFlyApi api = Bukkit.getServicesManager().load(AAFlyApi.class);      // ou null si absent
 * if (!api.apiVersion().isCompatibleWith(new ApiVersion(1, 0, 0))) { ... }
 * }</pre>
 *
 * <p><b>Fil d'exécution :</b> toutes les méthodes doivent être appelées depuis le main thread du serveur et
 * lèvent {@link IllegalStateException} sinon. Aucune ne bloque (aucun accès disque ou réseau).
 *
 * <p><b>Événements :</b> les modifications passent par le service de vol d'AAFly et émettent les événements
 * de {@code com.azurhosts.aafly.api.events} (cause {@link DisableCause#API}, raison {@code API}).
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public interface AAFlyApi {

    /**
     * @return version de l'API fournie ({@link ApiVersion#CURRENT})
     */
    ApiVersion apiVersion();

    /**
     * @param player joueur en ligne
     * @return solde de vol en secondes, ou {@code -1} si le profil n'est pas chargé
     */
    long remainingSeconds(UUID player);

    /**
     * @param player joueur en ligne
     * @return {@code true} si le vol AAFly est actif : voulu, non suspendu, et vol physique accordé par AAFly
     *         (faux si un autre plugin a retiré ce vol entre-temps)
     */
    boolean isFlightEnabled(UUID player);

    /**
     * @param player joueur
     * @return {@code true} si le joueur a le vol illimité ({@code aafly.unlimited})
     */
    boolean isUnlimited(Player player);

    /**
     * @param player joueur en ligne
     * @return {@code true} si le vol est voulu mais coupé par une règle ou par une menace proche
     */
    boolean isSuspended(UUID player);

    /**
     * Crédit de temps contrôlé (consommable, récompense…).
     *
     * <ul>
     *   <li>cap effectif = {@code min(request.capSeconds, limits.max-seconds)} ; {@code request.seconds} doit
     *       être dans {@code [1, cap]} ;</li>
     *   <li>{@link GrantMode#EXTEND} : {@code min(courant + secondes, cap)}, rien ajouté &rarr;
     *       {@link GrantResult.Status#CAP_REACHED} ;</li>
     *   <li>{@link GrantMode#REFRESH} : {@code max(courant, secondes)}, rien ajouté &rarr;
     *       {@link GrantResult.Status#ALREADY_ACTIVE} ;</li>
     *   <li>{@link GrantMode#DENY_IF_ACTIVE} : vol activé ou solde &gt; 0 &rarr;
     *       {@link GrantResult.Status#ALREADY_ACTIVE}, sinon crédite {@code secondes} ;</li>
     *   <li>joueur illimité &rarr; {@link GrantResult.Status#UNLIMITED} ; profil non chargé &rarr;
     *       {@link GrantResult.Status#NOT_LOADED} ; {@code api.allow-external-grants: false} ou timed-fly actif
     *       &rarr; {@link GrantResult.Status#DISABLED_BY_CONFIG} ;</li>
     *   <li>émet {@code FlightTimeChangeEvent} (avec {@code source}) : annulé ou réduit &rarr;
     *       {@link GrantResult.Status#DENIED_BY_RULE} ;</li>
     *   <li>{@code autoEnable} : active le vol après crédit si les règles l'autorisent ; si une règle refuse,
     *       le temps reste crédité ({@link GrantResult.Status#GRANTED}) et le joueur reçoit
     *       {@code not-allowed-here}.</li>
     * </ul>
     * Tout statut autre que {@link GrantResult.Status#GRANTED} signifie que rien n'a été crédité.
     *
     * @param player  joueur en ligne
     * @param request demande
     * @return résultat
     * @throws IllegalArgumentException si {@code request.seconds} est hors de {@code [1, cap]}
     * @throws IllegalStateException    hors main thread
     */
    GrantResult grant(Player player, GrantRequest request);

    /**
     * Active le vol AAFly en respectant le solde, les règles et la détection PVP.
     *
     * @param player joueur en ligne
     * @param source identifiant de l'appelant ({@code ^[a-z0-9_.:-]{1,32}$}), journalisé
     * @return {@code true} si le vol a été activé
     * @throws IllegalArgumentException si la source est invalide
     */
    boolean enable(Player player, String source);

    /**
     * Désactive le vol AAFly (atterrissage sûr selon la configuration).
     *
     * @param player joueur en ligne
     * @param source identifiant de l'appelant ({@code ^[a-z0-9_.:-]{1,32}$}), journalisé
     * @return {@code true} si le vol a été désactivé
     * @throws IllegalArgumentException si la source est invalide
     */
    boolean disable(Player player, String source);

    /**
     * Enregistre un type de condition personnalisé utilisable dans {@code fly-rules} / {@code decrement-rules}
     * (pris en compte au prochain chargement des règles, ex. {@code /aafly reload}).
     *
     * @param type    identifiant {@code ^[a-z0-9_]{1,64}$} (insensible à la casse)
     * @param factory fabrique
     * @throws IllegalArgumentException si le type est invalide ou déjà enregistré
     */
    void registerCondition(String type, ConditionFactory factory);

    /**
     * Ajoute un résolveur de menace pour la détection PVP (voir {@link ThreatResolver} pour la combinaison).
     * Retiré automatiquement à la désactivation du plugin qui l'a fourni.
     *
     * @param resolver résolveur
     */
    void registerThreatResolver(ThreatResolver resolver);

    /**
     * @param seconds durée en secondes
     * @return durée formatée selon la configuration d'AAFly (chaîne MiniMessage, à ne pas réinterpréter
     *         avec des données non fiables)
     */
    String formatTime(long seconds);
}
