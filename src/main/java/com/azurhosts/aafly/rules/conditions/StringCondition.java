package com.azurhosts.aafly.rules.conditions;

import com.azurhosts.aafly.rules.Condition;
import com.azurhosts.aafly.rules.ConditionFactory;
import com.azurhosts.aafly.rules.RuleContext;
import java.util.Locale;
import java.util.Objects;

/**
 * Comparaison de textes : {@code string_equals}, {@code string_equals_ignorecase},
 * {@code string_contains}, {@code string_contains_ignorecase}.
 *
 * <p>Paramètres : {@code input} (résolu via PlaceholderAPI) et {@code value} (peut aussi contenir des
 * placeholders). Les textes résolus sont tronqués à {@value #MAX_INPUT_LENGTH} caractères ; ils ne sont
 * jamais exécutés ni interprétés.
 */
public final class StringCondition implements Condition {

    /** Longueur maximale d'une entrée résolue comparée. */
    public static final int MAX_INPUT_LENGTH = 256;

    /** Opérateur de comparaison. */
    public enum Operator {
        /** Égalité stricte. */
        EQUALS("string_equals"),
        /** Égalité insensible à la casse. */
        EQUALS_IGNORECASE("string_equals_ignorecase"),
        /** Inclusion stricte. */
        CONTAINS("string_contains"),
        /** Inclusion insensible à la casse. */
        CONTAINS_IGNORECASE("string_contains_ignorecase");

        private final String type;

        Operator(String type) {
            this.type = type;
        }

        /**
         * @return identifiant du type de condition
         */
        public String type() {
            return type;
        }
    }

    private final Operator operator;
    private final String input;
    private final String value;
    private final boolean inputPlaceholders;
    private final boolean valuePlaceholders;

    /**
     * @param operator opérateur
     * @param input    entrée brute (placeholders autorisés)
     * @param value    valeur attendue (placeholders autorisés)
     */
    public StringCondition(Operator operator, String input, String value) {
        this.operator = Objects.requireNonNull(operator, "operator");
        this.input = Objects.requireNonNull(input, "input");
        this.value = Objects.requireNonNull(value, "value");
        this.inputPlaceholders = ConditionNodes.hasPlaceholder(input);
        this.valuePlaceholders = ConditionNodes.hasPlaceholder(value);
    }

    /**
     * @param operator opérateur
     * @return fabrique lisant {@code input} et {@code value}
     */
    public static ConditionFactory factory(Operator operator) {
        return node -> new StringCondition(operator,
                ConditionNodes.requireString(node, "input"),
                ConditionNodes.requireString(node, "value"));
    }

    @Override
    public boolean test(RuleContext context) {
        String in = inputPlaceholders ? context.resolve(input) : input;
        String expected = valuePlaceholders ? context.resolve(value) : value;
        return compare(operator, in, expected);
    }

    @Override
    public boolean usesPlaceholders() {
        return inputPlaceholders || valuePlaceholders;
    }

    /**
     * Comparaison pure (testable sans serveur).
     *
     * @param operator opérateur
     * @param input    entrée résolue
     * @param value    valeur attendue résolue
     * @return résultat
     */
    public static boolean compare(Operator operator, String input, String value) {
        String in = truncate(input);
        String expected = truncate(value);
        return switch (operator) {
            case EQUALS -> in.equals(expected);
            case EQUALS_IGNORECASE -> in.equalsIgnoreCase(expected);
            case CONTAINS -> in.contains(expected);
            case CONTAINS_IGNORECASE -> in.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
        };
    }

    static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_INPUT_LENGTH ? s.substring(0, MAX_INPUT_LENGTH) : s;
    }

    @Override
    public String toString() {
        return operator.type() + "[input=" + input + ", value=" + value + "]";
    }
}
