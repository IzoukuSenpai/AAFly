package com.azurhosts.aafly.token;

/** Issue de la création d'un jeton de vol. */
public enum TokenResult {
    /** Jeton créé et donné (ou déposé au sol si l'inventaire est plein). */
    SUCCESS,
    /** Jeton créé, inventaire plein : déposé aux pieds du joueur. */
    SUCCESS_DROPPED,
    /** Fonctionnalité désactivée. */
    DISABLED,
    /** Solde insuffisant (création par le joueur). */
    INSUFFICIENT_TIME,
    /** Montant ≤ 0 ou hors bornes. */
    INVALID_AMOUNT,
    /** Joueur illimité : ne peut pas convertir. */
    UNLIMITED,
    /** Mode timed-fly actif. */
    TIMED_FLY_LOCKED,
    /** Erreur de stockage : le temps éventuellement débité a été rendu. */
    STORAGE_ERROR;

    /**
     * @return {@code true} si un jeton a été remis
     */
    public boolean success() {
        return this == SUCCESS || this == SUCCESS_DROPPED;
    }
}
