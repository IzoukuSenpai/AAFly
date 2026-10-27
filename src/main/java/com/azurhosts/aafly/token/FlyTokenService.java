package com.azurhosts.aafly.token;

import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Jetons de vol (SPEC §7). Item porteur de PDC {@code aafly:token_id} (UUID en chaîne),
 * {@code aafly:seconds} (long), {@code aafly:sig} (HMAC hex). Les futures se complètent
 * <b>sur le main thread</b>. Méthodes à appeler depuis le main thread.
 */
public interface FlyTokenService {

    /** Namespace des clés PDC. */
    String PDC_NAMESPACE = "aafly";
    /** Clé PDC de l'identifiant du jeton. */
    String KEY_TOKEN_ID = "token_id";
    /** Clé PDC du montant en secondes. */
    String KEY_SECONDS = "seconds";
    /** Clé PDC de la signature. */
    String KEY_SIGNATURE = "sig";

    /**
     * {@code /fly convert <temps>} : débite le joueur (solde, bornes), insère le jeton en base puis donne l'item.
     * En cas d'échec de l'insertion, le temps débité est rendu.
     *
     * @param player  joueur
     * @param seconds montant
     * @return résultat
     */
    CompletableFuture<TokenResult> createForPlayer(Player player, long seconds);

    /**
     * {@code /aafly convert <joueur> <temps>} : crée un jeton sans débiter et le donne à la cible.
     *
     * @param actor   administrateur ou console ({@code null} pour l'API)
     * @param target  joueur qui reçoit l'item
     * @param seconds montant
     * @return résultat
     */
    CompletableFuture<TokenResult> createAdmin(@Nullable CommandSender actor, Player target, long seconds);

    /**
     * Encaisse le jeton tenu : vérifie la signature en temps constant, encaissement conditionnel en base
     * (async), crédit sur le main thread, puis consomme une unité de l'item <b>après</b> succès.
     * En cas d'échec, l'item reste intact.
     *
     * @param player joueur
     * @param item   item tenu
     * @return résultat
     */
    CompletableFuture<RedeemResult> redeem(Player player, ItemStack item);

    /**
     * Test rapide (présence des clés PDC, sans vérification de signature).
     *
     * @param item item quelconque (peut être {@code null})
     * @return {@code true} si l'item ressemble à un jeton AAFly
     */
    boolean isToken(@Nullable ItemStack item);
}
