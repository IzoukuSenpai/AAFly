package com.azurhosts.aafly.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Définition brute d'une condition lue dans la configuration, transmise à une {@link ConditionFactory}.
 *
 * <p>Les valeurs de {@code parameters} sont des types simples issus du YAML : {@link String},
 * {@link Number}, {@link Boolean}, {@link java.util.List} ou {@link Map} (sections imbriquées).
 * La clé {@code type} est exclue des paramètres. Ces valeurs viennent de la configuration du serveur :
 * les valider avant usage.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 *
 * @param type       type de la condition, normalisé en minuscules
 * @param parameters paramètres (copie non modifiable, ordre de déclaration conservé)
 */
public record ConditionDefinition(String type, Map<String, Object> parameters) {

    /**
     * Copie défensive des paramètres.
     *
     * @throws NullPointerException si {@code type} est nul
     */
    public ConditionDefinition {
        Objects.requireNonNull(type, "type");
        parameters = parameters == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    /**
     * @param key nom du paramètre
     * @return valeur textuelle du paramètre (nombre/booléen convertis), ou {@code null} s'il est absent
     */
    public String string(String key) {
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * @param key nom du paramètre obligatoire
     * @return valeur textuelle non vide
     * @throws IllegalArgumentException si le paramètre est absent ou vide
     */
    public String requireString(String key) {
        String value = string(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("paramètre '" + key + "' manquant");
        }
        return value;
    }

    /**
     * @param key      nom du paramètre
     * @param fallback valeur si absent
     * @return valeur numérique
     * @throws IllegalArgumentException si la valeur n'est pas un nombre fini
     */
    public double number(String key, double fallback) {
        Object value = parameters.get(key);
        if (value == null) {
            return fallback;
        }
        double d;
        if (value instanceof Number n) {
            d = n.doubleValue();
        } else {
            try {
                d = Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("paramètre '" + key + "' : nombre attendu", e);
            }
        }
        if (!Double.isFinite(d)) {
            throw new IllegalArgumentException("paramètre '" + key + "' : nombre fini attendu");
        }
        return d;
    }

    /**
     * @param key      nom du paramètre
     * @param fallback valeur si absent
     * @return valeur booléenne ({@code true}/{@code false}, insensible à la casse)
     * @throws IllegalArgumentException si la valeur n'est pas un booléen
     */
    public boolean bool(String key, boolean fallback) {
        Object value = parameters.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).trim();
        if (s.equalsIgnoreCase("true")) {
            return true;
        }
        if (s.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("paramètre '" + key + "' : booléen attendu");
    }
}
