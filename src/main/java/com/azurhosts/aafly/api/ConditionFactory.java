package com.azurhosts.aafly.api;

/**
 * Fabrique d'un type de condition personnalisé, enregistrée via {@link AAFlyApi#registerCondition}.
 *
 * <p>Exemple de configuration pour un type {@code my_plugin_level} :
 * <pre>{@code
 * fly-rules:
 *   low_level:
 *     priority: 5
 *     conditions:
 *       lvl: { type: my_plugin_level, max: 10 }
 *     result: DENY
 * }</pre>
 * Les paramètres communs ({@code negate: true}, préfixe {@code !} sur le type) sont gérés par AAFly.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
@FunctionalInterface
public interface ConditionFactory {

    /**
     * @param definition type et paramètres bruts lus dans la configuration
     * @return condition prête à l'emploi (jamais {@code null})
     * @throws IllegalArgumentException si un paramètre manque ou est invalide : la règle est alors ignorée
     *                                  avec un avertissement (jamais de crash)
     */
    FlightCondition create(ConditionDefinition definition);
}
