package com.azurhosts.aafly.token;

/** Issue de l'encaissement d'un jeton de vol. */
public enum RedeemResult {
    /** Jeton encaissé, temps crédité, une unité de l'item consommée. */
    SUCCESS,
    /** L'item n'est pas un jeton AAFly. */
    NOT_A_TOKEN,
    /** Signature absente, malformée ou fausse (item falsifié). */
    INVALID_SIGNATURE,
    /** Jeton déjà encaissé (ou inconnu en base) : anti-duplication. */
    ALREADY_REDEEMED,
    /** Le crédit dépasserait {@code limits.max-seconds} : item intact. */
    LIMIT_EXCEEDED,
    /** Fonctionnalité désactivée ({@code token.enabled: false}). */
    DISABLED,
    /** Mode timed-fly actif : les jetons ne peuvent pas être encaissés. */
    TIMED_FLY_LOCKED,
    /** Erreur de stockage : item intact. */
    STORAGE_ERROR
}
