package com.azurhosts.aafly.rules.conditions;

import com.azurhosts.aafly.rules.Condition;
import com.azurhosts.aafly.rules.RuleContext;
import java.util.Objects;

/**
 * Inverse une condition (paramètre commun {@code negate: true}, valable pour tous les types,
 * y compris ceux enregistrés via l'API).
 *
 * @param delegate condition inversée
 */
public record NegatedCondition(Condition delegate) implements Condition {

    public NegatedCondition {
        Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public boolean test(RuleContext context) {
        return !delegate.test(context);
    }

    @Override
    public boolean usesPlaceholders() {
        return delegate.usesPlaceholders();
    }
}
