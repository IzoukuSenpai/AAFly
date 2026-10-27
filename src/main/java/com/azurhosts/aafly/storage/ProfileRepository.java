package com.azurhosts.aafly.storage;

import com.azurhosts.aafly.core.FlightProfileSnapshot;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Accès SQL aux profils et aux jetons (SPEC §3). Implémentations : SQLite et MySQL.
 *
 * <p><b>Règles :</b> {@code PreparedStatement} uniquement, identifiants de tables fixes, aucune
 * concaténation de valeurs, mot de passe jamais loggé. Toutes les méthodes s'exécutent sur l'executor
 * I/O dédié ({@code AAFly-IO}) fourni à la construction ; les futures se complètent <b>sur ce thread I/O</b>
 * (l'appelant revient sur le main thread via le scheduler). Aucun accès à l'API Bukkit ici.
 */
public interface ProfileRepository extends AutoCloseable {

    /**
     * Crée/migre le schéma ({@code aafly_profiles}, {@code aafly_tokens}, {@code aafly_schema}).
     *
     * @return future complétée quand le schéma est prêt
     */
    CompletableFuture<Void> init();

    /**
     * @param uuid joueur
     * @return le profil persistant, ou vide si inconnu
     */
    CompletableFuture<Optional<FlightProfileSnapshot>> find(UUID uuid);

    /**
     * Recherche insensible à la casse par dernier pseudo connu.
     *
     * @param name pseudo validé {@code ^[A-Za-z0-9_]{1,16}$}
     * @return le profil le plus récemment mis à jour portant ce pseudo, ou vide
     */
    CompletableFuture<Optional<FlightProfileSnapshot>> findByName(String name);

    /**
     * Insère ou met à jour un profil (upsert).
     *
     * @param snapshot données à écrire
     * @return future complétée après écriture
     */
    CompletableFuture<Void> upsert(FlightProfileSnapshot snapshot);

    /**
     * Upsert groupé dans une transaction (batch).
     *
     * @param snapshots profils à écrire (peut être vide)
     * @return future complétée après commit
     */
    CompletableFuture<Void> saveAll(Collection<FlightProfileSnapshot> snapshots);

    /**
     * Enregistre un nouveau jeton (non encaissé).
     *
     * @param token jeton créé
     * @return future complétée après insertion
     */
    CompletableFuture<Void> insertToken(TokenRecord token);

    /**
     * Création d'un jeton par conversion : dans <b>une seule transaction</b>, upsert du profil déjà débité
     * puis insertion du jeton. Le débit est ainsi durable avant que l'item ne soit remis (pas de jeton
     * encaissable avec un solde non débité en base, même après un crash ou un changement de serveur).
     *
     * @param debited profil après débit
     * @param token   jeton créé
     * @return future complétée après commit (échec : rien n'est écrit)
     */
    CompletableFuture<Void> saveWithToken(FlightProfileSnapshot debited, TokenRecord token);

    /**
     * Encaissement conditionnel anti-duplication :
     * {@code UPDATE aafly_tokens SET redeemed_by=?, redeemed_at=? WHERE token_id=? AND seconds=? AND redeemed_at IS NULL},
     * succès si et seulement si exactement une ligne est modifiée.
     *
     * @param tokenId              jeton
     * @param seconds              montant attendu (doit correspondre à la ligne)
     * @param redeemer             joueur qui encaisse
     * @param redeemedAtEpochSeconds date d'encaissement
     * @return {@code true} si le jeton vient d'être encaissé par cet appel
     */
    CompletableFuture<Boolean> redeemToken(UUID tokenId, long seconds, UUID redeemer, long redeemedAtEpochSeconds);

    /**
     * Ferme le pool/connexions. Appelé après le flush final.
     */
    @Override
    void close();
}
