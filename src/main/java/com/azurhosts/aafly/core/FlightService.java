package com.azurhosts.aafly.core;

import com.azurhosts.aafly.api.events.FlightTimeChangeEvent;
import com.azurhosts.aafly.rules.RuleDecision;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Service central du vol (SPEC §4). <b>Toutes les méthodes s'appellent depuis le main thread.</b>
 *
 * <p>Les opérations sur le temps acceptent des joueurs hors-ligne déjà connus : elles renvoient un
 * {@link CompletableFuture} <b>complété sur le main thread</b> (immédiatement si le joueur est en ligne).
 * Les événements API ({@code FlightToggleEvent}, {@code FlightTimeChangeEvent}, {@code FlightExpireEvent})
 * sont émis ici. Les messages de résultat des commandes restent à la charge de l'appelant ;
 * les messages d'état (activation, expiration, règle) sont envoyés ici sauf si {@code silent}.
 */
public interface FlightService {

    /** Permission de vol illimité. */
    String PERM_UNLIMITED = "aafly.unlimited";
    /** Permission de contournement des règles. */
    String PERM_BYPASS_RULES = "aafly.bypass.rules";
    /** Alias wFly de {@link #PERM_UNLIMITED} (si {@code compat.wfly-permissions}). */
    String PERM_WFLY_UNLIMITED = "wfly.infinite.fly";
    /** Alias wFly de {@link #PERM_BYPASS_RULES} (si {@code compat.wfly-permissions}). */
    String PERM_WFLY_BYPASS = "wfly.bypass.fly";

    /**
     * Active le vol : vérifie temps &gt; 0 ou illimité, recharge timed-fly, règles (sauf bypass),
     * émet {@code FlightToggleEvent}, puis {@code setAllowFlight(true)}, {@code setFlying(true)} si en l'air,
     * applique la vitesse et exécute les actions {@code on-enable} (sauf {@code silent}).
     *
     * @param player joueur en ligne
     * @param cause  origine
     * @param silent {@code true} pour ne pas envoyer de message ni d'actions au joueur
     * @return résultat
     */
    ToggleResult enable(Player player, DisableCause cause, boolean silent);

    /**
     * Désactive le vol : émet {@code FlightToggleEvent} (non annulable pour EXPIRED),
     * {@code setAllowFlight(false)} sauf creative/spectator, SafeLanding si
     * {@code flight.tp-on-floor-when-disabled}. Pour {@link DisableCause#RULE} / {@link DisableCause#NEARBY},
     * le vol reste « voulu » ({@code flightEnabled}) et le profil est marqué suspendu.
     *
     * @param player joueur en ligne
     * @param cause  origine
     * @param silent {@code true} pour ne pas envoyer de message ni d'actions au joueur
     * @return résultat
     */
    ToggleResult disable(Player player, DisableCause cause, boolean silent);

    /**
     * Bascule l'état du vol (non silencieux).
     *
     * @param player joueur en ligne
     * @param cause  origine
     * @return résultat
     */
    ToggleResult toggle(Player player, DisableCause cause);

    /**
     * @param player joueur en ligne
     * @return {@code true} si le vol AAFly est actif (voulu et non suspendu)
     */
    boolean isEnabled(Player player);

    /**
     * Évalue les {@code fly-rules} pour ce joueur (en tenant compte du bypass).
     *
     * @param player joueur en ligne
     * @return décision (ALLOW si aucune règle ne correspond)
     */
    RuleDecision evaluate(Player player);

    /**
     * Ajoute du temps (saturé à {@code limits.max-seconds}).
     *
     * @param uuid    joueur (en ligne ou hors-ligne connu)
     * @param seconds quantité &gt; 0
     * @param actor   auteur (console, admin, joueur donneur) ou {@code null} pour l'API
     * @return résultat, complété sur le main thread
     */
    CompletableFuture<TimeChangeResult> addTime(UUID uuid, long seconds, @Nullable CommandSender actor);

    /**
     * Retire du temps (plancher 0). Si le temps tombe à 0 alors que le joueur vole, le vol est coupé (EXPIRED).
     *
     * @param uuid    joueur
     * @param seconds quantité &gt; 0
     * @param actor   auteur ou {@code null}
     * @return résultat, complété sur le main thread
     */
    CompletableFuture<TimeChangeResult> removeTime(UUID uuid, long seconds, @Nullable CommandSender actor);

    /**
     * Variante de {@link #addTime(UUID, long, CommandSender)} précisant la nature de la modification
     * transmise dans {@code FlightTimeChangeEvent} (don, jeton…). Ajout non cassant (méthode par défaut) :
     * l'implémentation AAFly la redéfinit ; par défaut la raison est déduite de l'auteur.
     *
     * @param uuid    joueur
     * @param seconds quantité &gt; 0
     * @param actor   auteur ou {@code null}
     * @param reason  nature de la modification
     * @return résultat, complété sur le main thread
     */
    default CompletableFuture<TimeChangeResult> addTime(UUID uuid, long seconds, @Nullable CommandSender actor,
                                                        FlightTimeChangeEvent.Reason reason) {
        return addTime(uuid, seconds, actor);
    }

    /**
     * Variante de {@link #removeTime(UUID, long, CommandSender)} précisant la nature de la modification.
     *
     * @param uuid    joueur
     * @param seconds quantité &gt; 0
     * @param actor   auteur ou {@code null}
     * @param reason  nature de la modification
     * @return résultat, complété sur le main thread
     */
    default CompletableFuture<TimeChangeResult> removeTime(UUID uuid, long seconds, @Nullable CommandSender actor,
                                                           FlightTimeChangeEvent.Reason reason) {
        return removeTime(uuid, seconds, actor);
    }

    /**
     * Variante de {@link #setTime(UUID, long, CommandSender)} précisant la nature de la modification.
     *
     * @param uuid    joueur
     * @param seconds nouvelle valeur ≥ 0
     * @param actor   auteur ou {@code null}
     * @param reason  nature de la modification
     * @return résultat, complété sur le main thread
     */
    default CompletableFuture<TimeChangeResult> setTime(UUID uuid, long seconds, @Nullable CommandSender actor,
                                                        FlightTimeChangeEvent.Reason reason) {
        return setTime(uuid, seconds, actor);
    }

    /**
     * Fixe le temps (borné à {@code [0, limits.max-seconds]}).
     *
     * @param uuid    joueur
     * @param seconds nouvelle valeur ≥ 0
     * @param actor   auteur ou {@code null}
     * @return résultat, complété sur le main thread
     */
    CompletableFuture<TimeChangeResult> setTime(UUID uuid, long seconds, @Nullable CommandSender actor);

    /**
     * Remet le temps à 0 (et coupe le vol s'il est actif).
     *
     * @param uuid  joueur
     * @param actor auteur ou {@code null}
     * @return résultat, complété sur le main thread
     */
    CompletableFuture<TimeChangeResult> reset(UUID uuid, @Nullable CommandSender actor);

    /**
     * Ajoute du temps à tous les joueurs en ligne.
     *
     * @param seconds quantité &gt; 0
     * @param actor   auteur ou {@code null}
     * @return nombre de joueurs effectivement modifiés
     */
    int addAll(long seconds, @Nullable CommandSender actor);

    /**
     * Retire du temps à tous les joueurs en ligne.
     *
     * @param seconds quantité &gt; 0
     * @param actor   auteur ou {@code null}
     * @return nombre de joueurs effectivement modifiés
     */
    int removeAll(long seconds, @Nullable CommandSender actor);

    /**
     * Vol illimité : permission {@code aafly.unlimited} (ou {@code wfly.infinite.fly} si compat),
     * ou OP si {@code flight.unlimited-for-op}.
     *
     * @param player joueur
     * @return {@code true} si illimité
     */
    boolean hasUnlimited(Player player);

    /**
     * Contournement des règles : {@code aafly.bypass.rules} (ou {@code wfly.bypass.fly} si compat).
     *
     * @param player joueur
     * @return {@code true} si les règles sont ignorées
     */
    boolean bypassesRules(Player player);

    /**
     * @param uuid joueur
     * @return le profil en cache des joueurs en ligne, ou {@code null}
     */
    @Nullable FlightProfile profile(UUID uuid);

    /**
     * @param uuid joueur
     * @return {@code true} si le vol est suspendu par une règle ou par un ennemi proche
     */
    boolean isSuspendedByRule(UUID uuid);

    /**
     * Applique au joueur l'état persistant de son profil à la connexion (décompte hors-ligne,
     * réactivation du vol si {@code flightEnabled}, vitesse).
     *
     * @param player  joueur qui vient de se connecter
     * @param profile son profil chargé
     */
    void restore(Player player, FlightProfile profile);
}
