package com.azurhosts.aafly.rules.conditions;

import com.azurhosts.aafly.rules.Condition;
import com.azurhosts.aafly.rules.ConditionFactory;
import com.azurhosts.aafly.rules.InvalidRuleException;
import com.azurhosts.aafly.rules.RuleContext;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Comparaisons numériques : {@code number_equals}, {@code number_greater}, {@code number_less}
 * ({@code input} + {@code value}) et {@code number_between} ({@code input} + {@code min} + {@code max},
 * bornes incluses).
 *
 * <p>Les bornes sont parsées au chargement ; l'entrée résolue est analysée de façon sûre
 * ({@link ConditionNodes#parseDouble(String)}). Une entrée non numérique ne correspond jamais.
 */
public final class NumberCondition implements Condition {

    /** Tolérance d'égalité. */
    public static final double EPSILON = 1.0E-9;

    /** Opérateur numérique. */
    public enum Operator {
        /** Égalité (à {@link #EPSILON} près). */
        EQUALS("number_equals"),
        /** Strictement supérieur. */
        GREATER("number_greater"),
        /** Strictement inférieur. */
        LESS("number_less"),
        /** Compris entre min et max inclus. */
        BETWEEN("number_between");

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
    private final double a;
    private final double b;
    private final boolean inputPlaceholders;

    /**
     * @param operator opérateur
     * @param input    entrée brute
     * @param a        valeur (ou minimum pour BETWEEN)
     * @param b        maximum pour BETWEEN (ignoré sinon)
     */
    public NumberCondition(Operator operator, String input, double a, double b) {
        this.operator = Objects.requireNonNull(operator, "operator");
        this.input = Objects.requireNonNull(input, "input");
        this.a = a;
        this.b = b;
        this.inputPlaceholders = ConditionNodes.hasPlaceholder(input);
    }

    /**
     * @param operator opérateur
     * @return fabrique
     */
    public static ConditionFactory factory(Operator operator) {
        return node -> {
            String input = ConditionNodes.requireString(node, "input");
            if (operator == Operator.BETWEEN) {
                double min = ConditionNodes.requireNumber(node, "min");
                double max = ConditionNodes.requireNumber(node, "max");
                if (min > max) {
                    throw new InvalidRuleException("number_between : 'min' doit être inférieur ou égal à 'max'");
                }
                return new NumberCondition(operator, input, min, max);
            }
            return new NumberCondition(operator, input, ConditionNodes.requireNumber(node, "value"), 0.0D);
        };
    }

    @Override
    public boolean test(RuleContext context) {
        return matches(inputPlaceholders ? context.resolve(input) : input);
    }

    /**
     * Test pur (sans serveur).
     *
     * @param resolvedInput entrée résolue
     * @return résultat ({@code false} si l'entrée n'est pas numérique)
     */
    public boolean matches(String resolvedInput) {
        OptionalDouble parsed = ConditionNodes.parseDouble(resolvedInput);
        if (parsed.isEmpty()) {
            return false;
        }
        double v = parsed.getAsDouble();
        return switch (operator) {
            case EQUALS -> Math.abs(v - a) <= EPSILON;
            case GREATER -> v > a;
            case LESS -> v < a;
            case BETWEEN -> v >= a && v <= b;
        };
    }

    @Override
    public boolean usesPlaceholders() {
        return inputPlaceholders;
    }

    @Override
    public String toString() {
        return operator.type() + "[input=" + input + "]";
    }
}
