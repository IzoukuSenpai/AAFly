package com.azurhosts.aafly.rules;

import com.azurhosts.aafly.action.Action;
import java.util.List;
import java.util.Objects;

/**
 * Règle chargée depuis {@code fly-rules} ou {@code decrement-rules}.
 *
 * @param name       nom (clé YAML)
 * @param priority   priorité (tri croissant : 0 est évaluée en premier)
 * @param conditions conditions (toutes doivent être vraies ; liste vide = toujours vraie)
 * @param result     résultat ; pour {@code decrement-rules} il est ignoré (convention : {@link RuleResult#DENY})
 * @param actions    actions exécutées quand la règle décide un DENY (liste éventuellement vide)
 */
public record Rule(String name, int priority, List<Condition> conditions, RuleResult result, List<Action> actions) {

    public Rule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(result, "result");
        conditions = List.copyOf(conditions);
        actions = List.copyOf(actions);
    }

    /**
     * @param context contexte
     * @return {@code true} si toutes les conditions sont remplies
     */
    public boolean matches(RuleContext context) {
        for (Condition c : conditions) {
            if (!c.test(context)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return {@code true} si au moins une condition résout des placeholders
     */
    public boolean usesPlaceholders() {
        for (Condition c : conditions) {
            if (c.usesPlaceholders()) {
                return true;
            }
        }
        return false;
    }
}
