package com.azurhosts.aafly.storage;

import com.azurhosts.aafly.core.FlightProfile;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * Cache des profils et persistance (SPEC §12) : cache des joueurs en ligne, cache LRU des profils
 * hors-ligne (256 entrées, expiration 10 min), dirty-set écrit toutes les {@code save-interval-seconds},
 * sauvegarde async à la déconnexion, flush synchrone (timeout 10 s) à l'arrêt.
 *
 * <p>Les méthodes de lecture du cache s'appellent depuis le main thread. Les futures renvoyant un
 * {@link FlightProfile} se complètent <b>sur le main thread</b>.
 */
public interface StorageManager {

    /** Délai maximal du flush synchrone à l'arrêt. */
    long SHUTDOWN_TIMEOUT_SECONDS = 10L;

    /**
     * Initialise le dépôt (schéma) et démarre la sauvegarde périodique.
     *
     * @return future complétée quand le stockage est opérationnel (sur le main thread)
     */
    CompletableFuture<Void> start();

    /**
     * @return type de stockage actif
     */
    StorageType type();

    /**
     * @return dépôt sous-jacent (pour les jetons)
     */
    ProfileRepository repository();

    /**
     * @return executor I/O dédié ({@code AAFly-IO}) — ne jamais y toucher à l'API Bukkit
     */
    Executor ioExecutor();

    /**
     * @return executor qui exécute sur le main thread (via le scheduler Bukkit)
     */
    Executor mainThreadExecutor();

    /**
     * @param uuid joueur
     * @return profil en cache des joueurs en ligne, ou {@code null}
     */
    @Nullable FlightProfile cached(UUID uuid);

    /**
     * @return vue non modifiable des profils des joueurs en ligne
     */
    Collection<FlightProfile> onlineProfiles();

    /**
     * Charge (ou crée) le profil d'un joueur qui se connecte et le place dans le cache en ligne.
     *
     * @param uuid joueur
     * @param name pseudo courant
     * @return profil, complété sur le main thread
     */
    CompletableFuture<FlightProfile> loadOnline(UUID uuid, String name);

    /**
     * Profil d'un joueur connu (en ligne, cache hors-ligne ou base), sans en créer.
     *
     * @param uuid joueur
     * @return profil ou vide si jamais vu, complété sur le main thread
     */
    CompletableFuture<Optional<FlightProfile>> loadExisting(UUID uuid);

    /**
     * Profil d'un joueur connu par son pseudo (pseudo validé avant l'appel).
     *
     * @param name pseudo
     * @return profil ou vide, complété sur le main thread
     */
    CompletableFuture<Optional<FlightProfile>> findByName(String name);

    /**
     * Ajoute le profil au dirty-set (écrit au prochain flush).
     *
     * @param profile profil modifié
     */
    void markDirty(FlightProfile profile);

    /**
     * Déconnexion : met à jour {@code lastUpdate}, sauvegarde async et retire du cache en ligne.
     *
     * @param uuid joueur
     */
    void unload(UUID uuid);

    /**
     * Écrit de façon asynchrone tous les profils {@code dirty}.
     *
     * @return future complétée après écriture
     */
    CompletableFuture<Void> flush();

    /**
     * Écrit de façon asynchrone (une ligne) le profil en mémoire de ce joueur s'il est modifié, sans toucher
     * au reste du dirty-set. En cas d'échec, le profil est remis en file pour le prochain flush.
     *
     * @param uuid joueur
     * @return future complétée après écriture (immédiatement si rien à écrire)
     */
    CompletableFuture<Void> save(UUID uuid);

    /**
     * Arrêt : flush synchrone (timeout {@link #SHUTDOWN_TIMEOUT_SECONDS} s), arrêt de l'executor, fermeture du dépôt.
     * Idempotent.
     */
    void shutdown();
}
