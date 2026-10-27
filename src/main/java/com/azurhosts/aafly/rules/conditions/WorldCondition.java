package com.azurhosts.aafly.rules.conditions;

import com.azurhosts.aafly.rules.Condition;
import com.azurhosts.aafly.rules.ConditionFactory;
import com.azurhosts.aafly.rules.RuleContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Condition {@code world} : vraie si le joueur se trouve dans l'un des mondes listés
 * ({@code worlds: [a, b]} ou {@code world: a}), comparaison insensible à la casse.
 */
public final class WorldCondition implements Condition {

    private final Set<String> worlds;

    /**
     * @param worlds noms de mondes
     */
    public WorldCondition(List<String> worlds) {
        Set<String> set = new LinkedHashSet<>();
        for (String w : worlds) {
            set.add(w.toLowerCase(Locale.ROOT));
        }
        this.worlds = Set.copyOf(set);
    }

    /**
     * @return fabrique lisant {@code worlds} ou {@code world}
     */
    public static ConditionFactory factory() {
        return node -> new WorldCondition(ConditionNodes.requireStringList(node, "worlds", "world"));
    }

    /**
     * Test pur.
     *
     * @param worldName nom du monde
     * @return {@code true} si le monde est listé
     */
    public boolean matches(String worldName) {
        return worldName != null && worlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean test(RuleContext context) {
        return matches(context.player().getWorld().getName());
    }

    @Override
    public String toString() {
        return "world" + worlds;
    }
}
