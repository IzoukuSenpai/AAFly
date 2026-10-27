package com.azurhosts.aafly.migration;

import java.util.Locale;
import java.util.Optional;

/**
 * Stratégie appliquée quand un joueur importé possède déjà un profil AAFly (SPEC-phase2 A9,
 * option {@code --merge=max|replace|skip}, défaut {@code max}).
 */
public enum MergeStrategy {
    /** Garde le plus grand des deux temps (jamais de perte pour le joueur). */
    MAX,
    /** Remplace le temps et l'état de vol par ceux de la source. */
    REPLACE,
    /** Ne touche pas aux profils existants. */
    SKIP;

    /** Stratégie par défaut. */
    public static final MergeStrategy DEFAULT = MAX;

    /**
     * @param raw valeur saisie ({@code max}, {@code replace}, {@code skip}, casse indifférente)
     * @return stratégie, ou vide si inconnue
     */
    public static Optional<MergeStrategy> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "max" -> Optional.of(MAX);
            case "replace" -> Optional.of(REPLACE);
            case "skip" -> Optional.of(SKIP);
            default -> Optional.empty();
        };
    }

    /**
     * @return nom en minuscules (affichage)
     */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
