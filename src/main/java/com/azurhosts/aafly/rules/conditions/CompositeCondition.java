package com.azurhosts.aafly.rules.conditions;

import com.azurhosts.aafly.rules.Condition;
import com.azurhosts.aafly.rules.RuleContext;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Condition composite (SPEC-phase2 A1) : {@code any} (alias {@code or}) ou {@code all} (alias {@code and}),
 * évaluée en court-circuit dans l'ordre de déclaration des enfants.
 *
 * <p>Une composite n'est jamais vide : le chargement refuse {@code any}/{@code all} sans enfant
 * (jamais de {@code true} silencieux). Profondeur et nombre d'enfants sont bornés au chargement
 * ({@link #MAX_DEPTH}, {@link #MAX_CHILDREN}).
 *
 * <p>AAFly — Developped by AzurHOSTS.com and powered by AzurWare.fr.
 *
 * @param mode     {@link Mode#ANY} ou {@link Mode#ALL}
 * @param children enfants (au moins un)
 */
public record CompositeCondition(Mode mode, List<Condition> children) implements Condition {

    /** Profondeur maximale d'imbrication (une condition de premier niveau est à la profondeur 1). */
    public static final int MAX_DEPTH = 8;
    /** Nombre maximal d'enfants par nœud composite. */
    public static final int MAX_CHILDREN = 32;

    /** Mode de composition. */
    public enum Mode {
        /** Au moins un enfant vrai (OU). */
        ANY,
        /** Tous les enfants vrais (ET). */
        ALL
    }

    public CompositeCondition {
        Objects.requireNonNull(mode, "mode");
        children = List.copyOf(children);
        if (children.isEmpty()) {
            throw new IllegalArgumentException("composite sans enfant");
        }
        if (children.size() > MAX_CHILDREN) {
            throw new IllegalArgumentException("trop d'enfants");
        }
    }

    /**
     * @param type type normalisé (minuscules)
     * @return mode correspondant, ou {@code null} si ce n'est pas un type composite
     */
    public static @Nullable Mode modeOf(@Nullable String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "any", "or" -> Mode.ANY;
            case "all", "and" -> Mode.ALL;
            default -> null;
        };
    }

    @Override
    public boolean test(RuleContext context) {
        if (mode == Mode.ANY) {
            for (Condition c : children) {
                if (c.test(context)) {
                    return true;
                }
            }
            return false;
        }
        for (Condition c : children) {
            if (!c.test(context)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean usesPlaceholders() {
        for (Condition c : children) {
            if (c.usesPlaceholders()) {
                return true;
            }
        }
        return false;
    }
}
