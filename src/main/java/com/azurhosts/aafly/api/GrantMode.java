package com.azurhosts.aafly.api;

/**
 * Manière dont un crédit de temps ({@link AAFlyApi#grant}) se combine au solde existant.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public enum GrantMode {
    /**
     * Ajoute au solde, plafonné : {@code min(courant + secondes, cap)}. Rien ajouté :
     * {@link GrantResult.Status#CAP_REACHED}.
     */
    EXTEND,
    /**
     * Porte le solde à au moins la durée demandée : {@code max(courant, secondes)}. Rien ajouté :
     * {@link GrantResult.Status#ALREADY_ACTIVE}.
     */
    REFRESH,
    /**
     * Crédite uniquement si le vol est désactivé <b>et</b> le solde nul ; sinon
     * {@link GrantResult.Status#ALREADY_ACTIVE}.
     */
    DENY_IF_ACTIVE
}
