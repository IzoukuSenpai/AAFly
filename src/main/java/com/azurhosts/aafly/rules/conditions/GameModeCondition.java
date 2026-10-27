package com.azurhosts.aafly.rules.conditions;

import com.azurhosts.aafly.rules.Condition;
import com.azurhosts.aafly.rules.ConditionFactory;
import com.azurhosts.aafly.rules.InvalidRuleException;
import com.azurhosts.aafly.rules.RuleContext;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.GameMode;

/**
 * Condition {@code gamemode} : vraie si le joueur est dans l'un des modes listés
 * ({@code gamemodes: [SURVIVAL, ADVENTURE]} ou {@code gamemode: SURVIVAL}).
 */
public final class GameModeCondition implements Condition {

    private final Set<GameMode> modes;

    /**
     * @param modes modes acceptés
     */
    public GameModeCondition(Set<GameMode> modes) {
        this.modes = modes.isEmpty() ? EnumSet.noneOf(GameMode.class) : EnumSet.copyOf(modes);
    }

    /**
     * @return fabrique lisant {@code gamemodes} ou {@code gamemode}
     */
    public static ConditionFactory factory() {
        return node -> {
            List<String> raws = ConditionNodes.requireStringList(node, "gamemodes", "gamemode");
            Set<GameMode> modes = EnumSet.noneOf(GameMode.class);
            for (String raw : raws) {
                modes.add(parse(raw));
            }
            return new GameModeCondition(modes);
        };
    }

    /**
     * @param raw nom du mode (insensible à la casse)
     * @return mode
     * @throws InvalidRuleException si le mode est inconnu
     */
    public static GameMode parse(String raw) throws InvalidRuleException {
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (GameMode m : GameMode.values()) {
            if (m.name().equals(key)) {
                return m;
            }
        }
        throw new InvalidRuleException("mode de jeu inconnu (SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR)");
    }

    /**
     * Test pur.
     *
     * @param mode mode du joueur
     * @return {@code true} si listé
     */
    public boolean matches(GameMode mode) {
        return mode != null && modes.contains(mode);
    }

    @Override
    public boolean test(RuleContext context) {
        return matches(context.player().getGameMode());
    }

    @Override
    public String toString() {
        return "gamemode" + modes;
    }
}
