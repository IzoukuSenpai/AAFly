package com.azurhosts.aafly.rules.conditions;

import com.azurhosts.aafly.rules.Condition;
import com.azurhosts.aafly.rules.ConditionFactory;
import com.azurhosts.aafly.rules.InvalidRuleException;
import com.azurhosts.aafly.rules.RuleContext;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Condition {@code string_regex} : {@code input} (résolu via PlaceholderAPI, tronqué à 256 caractères)
 * comparé à {@code pattern} (expression régulière compilée une seule fois au chargement).
 *
 * <p>Par défaut l'entrée entière doit correspondre ({@code full-match: true}) ; avec
 * {@code full-match: false}, une correspondance partielle suffit. Le motif provient uniquement
 * de la configuration (fiable) ; l'entrée tronquée limite le coût d'évaluation.
 */
public final class RegexCondition implements Condition {

    /** Longueur maximale d'un motif. */
    public static final int MAX_PATTERN_LENGTH = 256;

    private final String input;
    private final Pattern pattern;
    private final boolean fullMatch;
    private final boolean inputPlaceholders;

    /**
     * @param input     entrée brute
     * @param pattern   motif compilé
     * @param fullMatch correspondance complète exigée
     */
    public RegexCondition(String input, Pattern pattern, boolean fullMatch) {
        this.input = Objects.requireNonNull(input, "input");
        this.pattern = Objects.requireNonNull(pattern, "pattern");
        this.fullMatch = fullMatch;
        this.inputPlaceholders = ConditionNodes.hasPlaceholder(input);
    }

    /**
     * @return fabrique lisant {@code input}, {@code pattern} (ou {@code value}) et {@code full-match}
     */
    public static ConditionFactory factory() {
        return node -> {
            String input = ConditionNodes.requireString(node, "input");
            String raw = ConditionNodes.optionalString(node, "pattern");
            if (raw == null) {
                raw = ConditionNodes.requireString(node, "value");
            }
            boolean full = ConditionNodes.optionalBoolean(node, "full-match", true);
            return new RegexCondition(input, compile(raw), full);
        };
    }

    /**
     * Compile un motif de configuration.
     *
     * @param raw motif
     * @return motif compilé
     * @throws InvalidRuleException si le motif est trop long ou invalide
     */
    public static Pattern compile(String raw) throws InvalidRuleException {
        if (raw.length() > MAX_PATTERN_LENGTH) {
            throw new InvalidRuleException("motif regex trop long (max " + MAX_PATTERN_LENGTH + " caractères)");
        }
        try {
            return Pattern.compile(raw);
        } catch (PatternSyntaxException e) {
            throw new InvalidRuleException("motif regex invalide : " + e.getDescription());
        }
    }

    @Override
    public boolean test(RuleContext context) {
        String in = inputPlaceholders ? context.resolve(input) : input;
        return matches(in);
    }

    /**
     * Test pur (sans serveur).
     *
     * @param resolvedInput entrée déjà résolue
     * @return résultat
     */
    public boolean matches(String resolvedInput) {
        String in = StringCondition.truncate(resolvedInput);
        return fullMatch ? pattern.matcher(in).matches() : pattern.matcher(in).find();
    }

    @Override
    public boolean usesPlaceholders() {
        return inputPlaceholders;
    }

    @Override
    public String toString() {
        return "string_regex[input=" + input + ", pattern=" + pattern.pattern() + ", fullMatch=" + fullMatch + "]";
    }
}
