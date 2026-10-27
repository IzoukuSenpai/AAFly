package com.azurhosts.aafly.rules;

import java.util.Set;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Registre extensible des types de conditions (SPEC §5). Types intégrés : {@code string_equals},
 * {@code string_equals_ignorecase}, {@code string_contains}, {@code string_contains_ignorecase},
 * {@code string_regex}, {@code number_equals}, {@code number_greater}, {@code number_less},
 * {@code number_between}, {@code has_permission}, {@code world}, {@code gamemode}.
 * Les types sont normalisés en minuscules. Thread-safe (enregistrements possibles depuis l'API).
 * Composites réservés (SPEC-phase2 A1) : {@code any}/{@code or}, {@code all}/{@code and} ; négation par
 * préfixe {@code !} ou {@code negate: true}.
 */
public interface ConditionRegistry {

    /**
     * Enregistre un type.
     *
     * @param type    identifiant {@code ^[a-z0-9_]{1,64}$} (normalisé en minuscules)
     * @param factory fabrique
     * @throws IllegalArgumentException si le type est invalide ou déjà enregistré
     */
    void register(String type, ConditionFactory factory);

    /**
     * Retire un type enregistré par un plugin tiers (ex. à sa désactivation). Les types intégrés et
     * composites ne peuvent pas être retirés. Les règles déjà chargées gardent leur condition jusqu'au
     * prochain rechargement des règles.
     *
     * @param type type (insensible à la casse)
     * @return {@code true} si le type a été retiré
     */
    default boolean unregister(String type) {
        return false;
    }

    /**
     * Construit une condition.
     *
     * @param type type demandé (insensible à la casse)
     * @param node nœud de configuration de la condition
     * @return condition
     * @throws InvalidRuleException si le type est inconnu ou les paramètres invalides
     */
    Condition create(String type, ConfigurationNode node) throws InvalidRuleException;

    /**
     * @param type type (insensible à la casse)
     * @return {@code true} si le type est connu
     */
    boolean has(String type);

    /**
     * @return types enregistrés (copie)
     */
    Set<String> types();
}
