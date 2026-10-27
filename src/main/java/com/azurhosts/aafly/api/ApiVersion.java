package com.azurhosts.aafly.api;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version sémantique de l'API publique AAFly (indépendante de la version du plugin).
 *
 * <p>Règle de compatibilité : même version majeure, et version fournie supérieure ou égale à la version
 * requise (une version mineure ou corrective plus récente n'apporte que des ajouts rétrocompatibles).
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 *
 * @param major version majeure (ruptures de compatibilité), &ge; 0
 * @param minor version mineure (ajouts compatibles), &ge; 0
 * @param patch version corrective, &ge; 0
 */
public record ApiVersion(int major, int minor, int patch) implements Comparable<ApiVersion> {

    /** Version de l'API fournie par cette version d'AAFly. */
    public static final ApiVersion CURRENT = new ApiVersion(1, 0, 0);

    private static final Pattern FORMAT = Pattern.compile("^(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})$");

    /**
     * Valide les composantes.
     *
     * @throws IllegalArgumentException si une composante est négative
     */
    public ApiVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Composantes de version négatives interdites");
        }
    }

    /**
     * Lit une version {@code "majeur.mineur.correctif"} (ex. {@code "1.0.0"}).
     *
     * @param text texte à lire
     * @return la version
     * @throws IllegalArgumentException si le format est invalide
     */
    public static ApiVersion parse(String text) {
        Objects.requireNonNull(text, "text");
        Matcher m = FORMAT.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Version d'API invalide (attendu majeur.mineur.correctif)");
        }
        return new ApiVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
    }

    /**
     * Indique si cette version (celle fournie par AAFly) satisfait la version requise par un plugin tiers.
     *
     * @param required version minimale attendue par l'appelant
     * @return {@code true} si même majeure et {@code this >= required}
     */
    public boolean isCompatibleWith(ApiVersion required) {
        Objects.requireNonNull(required, "required");
        return major == required.major && compareTo(required) >= 0;
    }

    @Override
    public int compareTo(ApiVersion other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        return c != 0 ? c : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
