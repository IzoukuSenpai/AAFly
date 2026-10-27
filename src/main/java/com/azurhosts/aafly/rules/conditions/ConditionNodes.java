package com.azurhosts.aafly.rules.conditions;

import com.azurhosts.aafly.rules.InvalidRuleException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Lecture validée des paramètres d'une condition. Toutes les erreurs sont des
 * {@link InvalidRuleException} au message lisible (en français), jamais des exceptions brutes.
 *
 * <p>AAFly — Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class ConditionNodes {

    /** Longueur maximale d'un texte de condition lu dans la configuration. */
    public static final int MAX_TEXT_LENGTH = 512;
    /** Nombre maximal d'éléments dans une liste de condition (mondes, modes de jeu…). */
    public static final int MAX_LIST_SIZE = 256;

    private static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d{1,30}(\\.\\d{0,30})?|\\.\\d{1,30})([eE][+-]?\\d{1,3})?$");
    private static final int MAX_NUMBER_LENGTH = 64;

    private ConditionNodes() {
    }

    /**
     * Lit un texte obligatoire (scalaire, non vide, longueur bornée).
     *
     * @param node nœud de la condition
     * @param key  clé du paramètre
     * @return texte
     * @throws InvalidRuleException si absent, non scalaire, vide ou trop long
     */
    public static String requireString(ConfigurationNode node, String key) throws InvalidRuleException {
        String value = optionalString(node, key);
        if (value == null) {
            throw new InvalidRuleException("paramètre '" + key + "' manquant");
        }
        return value;
    }

    /**
     * Lit un texte facultatif.
     *
     * @param node nœud de la condition
     * @param key  clé du paramètre
     * @return texte, ou {@code null} s'il est absent
     * @throws InvalidRuleException si la valeur n'est pas scalaire, est vide ou trop longue
     */
    public static @Nullable String optionalString(ConfigurationNode node, String key) throws InvalidRuleException {
        ConfigurationNode child = node.node(key);
        if (child.virtual() || child.raw() == null) {
            return null;
        }
        if (child.isMap() || child.isList()) {
            throw new InvalidRuleException("paramètre '" + key + "' : texte attendu");
        }
        String value = String.valueOf(child.raw());
        if (value.isEmpty()) {
            throw new InvalidRuleException("paramètre '" + key + "' vide");
        }
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new InvalidRuleException("paramètre '" + key + "' trop long (max " + MAX_TEXT_LENGTH + " caractères)");
        }
        return value;
    }

    /**
     * Lit un nombre obligatoire (nombre YAML ou texte numérique), fini.
     *
     * @param node nœud de la condition
     * @param key  clé du paramètre
     * @return valeur
     * @throws InvalidRuleException si absent ou non numérique
     */
    public static double requireNumber(ConfigurationNode node, String key) throws InvalidRuleException {
        ConfigurationNode child = node.node(key);
        if (child.virtual() || child.raw() == null) {
            throw new InvalidRuleException("paramètre '" + key + "' manquant");
        }
        Object raw = child.raw();
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                throw new InvalidRuleException("paramètre '" + key + "' : nombre fini attendu");
            }
            return d;
        }
        if (child.isMap() || child.isList()) {
            throw new InvalidRuleException("paramètre '" + key + "' : nombre attendu");
        }
        OptionalDouble parsed = parseDouble(String.valueOf(raw));
        if (parsed.isEmpty()) {
            throw new InvalidRuleException("paramètre '" + key + "' : nombre attendu");
        }
        return parsed.getAsDouble();
    }

    /**
     * Lit un booléen facultatif ({@code true}/{@code false}, insensible à la casse).
     *
     * @param node         nœud de la condition
     * @param key          clé
     * @param defaultValue valeur si absent
     * @return booléen
     * @throws InvalidRuleException si la valeur n'est pas un booléen
     */
    public static boolean optionalBoolean(ConfigurationNode node, String key, boolean defaultValue)
            throws InvalidRuleException {
        ConfigurationNode child = node.node(key);
        if (child.virtual() || child.raw() == null) {
            return defaultValue;
        }
        Object raw = child.raw();
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new InvalidRuleException("paramètre '" + key + "' : true ou false attendu");
        };
    }

    /**
     * Lit une liste de textes (une valeur scalaire est acceptée comme liste à un élément).
     * La première clé présente parmi {@code keys} est utilisée.
     *
     * @param node nœud de la condition
     * @param keys clés acceptées, par ordre de préférence
     * @return liste non vide
     * @throws InvalidRuleException si absente, vide, trop longue ou mal formée
     */
    public static List<String> requireStringList(ConfigurationNode node, String... keys) throws InvalidRuleException {
        for (String key : keys) {
            ConfigurationNode child = node.node(key);
            if (child.virtual() || child.raw() == null) {
                continue;
            }
            List<String> out = new ArrayList<>();
            if (child.isList()) {
                for (ConfigurationNode element : child.childrenList()) {
                    if (element.isMap() || element.isList() || element.raw() == null) {
                        throw new InvalidRuleException("paramètre '" + key + "' : liste de textes attendue");
                    }
                    String v = String.valueOf(element.raw()).trim();
                    if (v.isEmpty() || v.length() > MAX_TEXT_LENGTH) {
                        throw new InvalidRuleException("paramètre '" + key + "' : élément vide ou trop long");
                    }
                    out.add(v);
                }
            } else if (child.isMap()) {
                throw new InvalidRuleException("paramètre '" + key + "' : liste de textes attendue");
            } else {
                String v = String.valueOf(child.raw()).trim();
                if (v.isEmpty() || v.length() > MAX_TEXT_LENGTH) {
                    throw new InvalidRuleException("paramètre '" + key + "' vide ou trop long");
                }
                out.add(v);
            }
            if (out.isEmpty()) {
                throw new InvalidRuleException("paramètre '" + key + "' : liste vide");
            }
            if (out.size() > MAX_LIST_SIZE) {
                throw new InvalidRuleException("paramètre '" + key + "' : trop d'éléments (max " + MAX_LIST_SIZE + ")");
            }
            return List.copyOf(out);
        }
        throw new InvalidRuleException("paramètre '" + keys[0] + "' manquant");
    }

    /**
     * Analyse sûre d'un nombre décimal issu d'un placeholder : longueur bornée, format strict,
     * virgule acceptée comme séparateur décimal (ou de milliers si un point est aussi présent),
     * espaces (y compris insécables) ignorés, valeurs non finies refusées. Ne lève jamais d'exception.
     *
     * @param input texte (peut être {@code null})
     * @return valeur, ou vide si le texte n'est pas un nombre
     */
    public static OptionalDouble parseDouble(@Nullable String input) {
        if (input == null) {
            return OptionalDouble.empty();
        }
        String s = input.trim();
        if (s.isEmpty() || s.length() > MAX_NUMBER_LENGTH) {
            return OptionalDouble.empty();
        }
        s = s.replace(" ", "").replace(" ", "").replace(" ", "");
        if (s.indexOf(',') >= 0) {
            s = s.indexOf('.') >= 0 ? s.replace(",", "") : s.replace(',', '.');
        }
        if (!NUMBER.matcher(s).matches()) {
            return OptionalDouble.empty();
        }
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /**
     * @param text texte de configuration
     * @return {@code true} s'il contient au moins un placeholder potentiel {@code %…%}
     */
    public static boolean hasPlaceholder(String text) {
        int first = text.indexOf('%');
        return first >= 0 && text.indexOf('%', first + 1) > first;
    }
}
