package com.azurhosts.aafly.rules.conditions;

import com.azurhosts.aafly.rules.ConditionRegistry;

/**
 * Enregistre les types de conditions intégrés (SPEC §5).
 */
public final class BuiltinConditions {

    private BuiltinConditions() {
    }

    /**
     * @param registry registre cible
     */
    public static void registerAll(ConditionRegistry registry) {
        for (StringCondition.Operator op : StringCondition.Operator.values()) {
            registry.register(op.type(), StringCondition.factory(op));
        }
        registry.register("string_regex", RegexCondition.factory());
        for (NumberCondition.Operator op : NumberCondition.Operator.values()) {
            registry.register(op.type(), NumberCondition.factory(op));
        }
        registry.register("has_permission", PermissionCondition.factory());
        registry.register("world", WorldCondition.factory());
        registry.register("gamemode", GameModeCondition.factory());
    }
}
