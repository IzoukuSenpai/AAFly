package com.azurhosts.aafly.api;

import java.util.Objects;

/**
 * Résultat d'un {@link AAFlyApi#grant crédit de temps}.
 *
 * <p>Seul {@link Status#GRANTED} signifie que du temps a été crédité ; pour tout autre statut, le solde n'a
 * pas été augmenté et l'appelant doit rembourser ce qu'il a éventuellement prélevé (objet consommé, coût…).
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 *
 * @param status           issue du crédit
 * @param remainingSeconds solde du joueur après l'opération (en secondes), {@code -1} si inconnu
 *                         ({@link Status#NOT_LOADED}, {@link Status#UNLIMITED})
 */
public record GrantResult(Status status, long remainingSeconds) {

    /** Issue d'un crédit de temps. */
    public enum Status {
        /** Temps crédité (le vol peut ne pas avoir été activé si une règle l'interdit). */
        GRANTED,
        /** Rien crédité : vol déjà actif ou solde déjà suffisant ({@code REFRESH}, {@code DENY_IF_ACTIVE}). */
        ALREADY_ACTIVE,
        /** Rien crédité : le plafond est atteint ({@code EXTEND}). */
        CAP_REACHED,
        /** Rien crédité : refusé (ou réduit) par un listener de {@code FlightTimeChangeEvent}. */
        DENIED_BY_RULE,
        /** Rien crédité : le joueur a le vol illimité. */
        UNLIMITED,
        /** Rien crédité : le profil du joueur n'est pas encore chargé (connexion en cours). */
        NOT_LOADED,
        /**
         * Rien crédité : crédits externes désactivés ({@code api.allow-external-grants: false}) ou temps
         * géré par le mode timed-fly.
         */
        DISABLED_BY_CONFIG
    }

    /**
     * Valide le statut.
     *
     * @throws NullPointerException si {@code status} est nul
     */
    public GrantResult {
        Objects.requireNonNull(status, "status");
    }

    /**
     * @return {@code true} si du temps a été crédité
     */
    public boolean granted() {
        return status == Status.GRANTED;
    }
}
