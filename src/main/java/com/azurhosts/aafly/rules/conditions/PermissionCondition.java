package com.azurhosts.aafly.rules.conditions;

import com.azurhosts.aafly.rules.Condition;
import com.azurhosts.aafly.rules.ConditionFactory;
import com.azurhosts.aafly.rules.InvalidRuleException;
import com.azurhosts.aafly.rules.RuleContext;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Condition {@code has_permission} : vraie si le joueur possède {@code permission}.
 */
public final class PermissionCondition implements Condition {

    /** Format accepté d'un nœud de permission. */
    public static final Pattern PERMISSION = Pattern.compile("^[A-Za-z0-9_.*\\-]{1,128}$");

    private final String permission;

    /**
     * @param permission nœud de permission validé
     */
    public PermissionCondition(String permission) {
        this.permission = Objects.requireNonNull(permission, "permission");
    }

    /**
     * @return fabrique lisant {@code permission}
     */
    public static ConditionFactory factory() {
        return node -> {
            String perm = ConditionNodes.requireString(node, "permission").trim();
            if (!PERMISSION.matcher(perm).matches()) {
                throw new InvalidRuleException("permission invalide (caractères autorisés : lettres, chiffres, _ . * -)");
            }
            return new PermissionCondition(perm);
        };
    }

    /**
     * @return nœud de permission
     */
    public String permission() {
        return permission;
    }

    @Override
    public boolean test(RuleContext context) {
        return context.player().hasPermission(permission);
    }

    @Override
    public String toString() {
        return "has_permission[" + permission + "]";
    }
}
