package com.azurhosts.aafly.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Demande de crédit de temps de vol adressée à {@link AAFlyApi#grant}.
 *
 * <p>Validation à la construction (échec rapide) : {@code seconds} et {@code capSeconds} &ge; 1,
 * {@code mode} non nul, {@code source} conforme à {@link #SOURCE_PATTERN}. La borne supérieure
 * ({@code seconds <= min(capSeconds, limits.max-seconds)}) dépend de la configuration du serveur et est
 * vérifiée par {@link AAFlyApi#grant}.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 *
 * @param seconds    durée à créditer, en secondes (&ge; 1)
 * @param mode       combinaison avec le solde existant
 * @param capSeconds plafond de solde voulu par l'appelant (&ge; 1), réduit à {@code limits.max-seconds}
 * @param autoEnable {@code true} pour activer le vol après crédit si les règles l'autorisent
 * @param source     identifiant de l'appelant, journalisé et transmis dans {@code FlightTimeChangeEvent}
 *                   (ex. {@code "aaitems"})
 */
public record GrantRequest(long seconds, GrantMode mode, long capSeconds, boolean autoEnable, String source) {

    /** Format d'une source : minuscules, chiffres et {@code _ . : -}, de 1 à 32 caractères. */
    public static final Pattern SOURCE_PATTERN = Pattern.compile("^[a-z0-9_.:-]{1,32}$");

    /**
     * Valide la demande.
     *
     * @throws IllegalArgumentException si une durée est &lt; 1 ou si la source est invalide
     * @throws NullPointerException     si {@code mode} ou {@code source} est nul
     */
    public GrantRequest {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(source, "source");
        if (seconds < 1L) {
            throw new IllegalArgumentException("seconds doit être >= 1");
        }
        if (capSeconds < 1L) {
            throw new IllegalArgumentException("capSeconds doit être >= 1");
        }
        if (!isValidSource(source)) {
            throw new IllegalArgumentException("source invalide (attendu ^[a-z0-9_.:-]{1,32}$)");
        }
    }

    /**
     * @param source source à tester (peut être {@code null})
     * @return {@code true} si la source respecte {@link #SOURCE_PATTERN}
     */
    public static boolean isValidSource(String source) {
        return source != null && SOURCE_PATTERN.matcher(source).matches();
    }
}
