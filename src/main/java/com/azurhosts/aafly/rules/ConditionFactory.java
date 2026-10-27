package com.azurhosts.aafly.rules;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Fabrique d'une condition à partir de son nœud de configuration
 * (ex. {@code { type: string_equals, input: "%x%", value: "true" }}).
 */
@FunctionalInterface
public interface ConditionFactory {

    /**
     * @param node nœud de la condition (contient au moins {@code type})
     * @return condition prête à l'emploi
     * @throws InvalidRuleException si un paramètre manque ou est invalide
     */
    Condition create(ConfigurationNode node) throws InvalidRuleException;
}
