package com.azurhosts.aafly.rules;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Décision du moteur de règles.
 *
 * @param result  résultat retenu
 * @param matched règle qui a décidé, ou {@code null} si aucune règle ne correspond (ALLOW par défaut)
 */
public record RuleDecision(RuleResult result, @Nullable Rule matched) {

    /** Décision par défaut quand aucune règle ne correspond. */
    public static final RuleDecision DEFAULT_ALLOW = new RuleDecision(RuleResult.ALLOW, null);

    public RuleDecision {
        Objects.requireNonNull(result, "result");
    }

    /**
     * @return {@code true} si le vol est autorisé
     */
    public boolean allowed() {
        return result == RuleResult.ALLOW;
    }
}
