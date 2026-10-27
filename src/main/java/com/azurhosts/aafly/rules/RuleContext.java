package com.azurhosts.aafly.rules;

import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Contexte d'évaluation d'une règle (main thread).
 *
 * <p>Un contexte vit le temps d'une évaluation (au plus un tick du ticker) : les entrées résolues via
 * PlaceholderAPI y sont mémorisées, de sorte qu'un même placeholder utilisé par plusieurs règles
 * ({@code fly-rules} et {@code decrement-rules}) n'est résolu qu'une fois. Ne pas conserver un contexte
 * au-delà de ce périmètre (valeurs figées). Non thread-safe.
 */
public final class RuleContext {

    private final Player player;
    private final FlightProfile profile;
    private final PlaceholderBridge placeholders;
    /** Entrées déjà résolues pour ce contexte (créée au premier besoin). */
    private @Nullable Map<String, String> resolved;

    /**
     * @param player       joueur évalué
     * @param profile      son profil de vol
     * @param placeholders pont PlaceholderAPI (no-op si absent)
     */
    public RuleContext(Player player, FlightProfile profile, PlaceholderBridge placeholders) {
        this.player = Objects.requireNonNull(player, "player");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.placeholders = Objects.requireNonNull(placeholders, "placeholders");
    }

    /** @return joueur évalué */
    public Player player() {
        return player;
    }

    /** @return son profil de vol */
    public FlightProfile profile() {
        return profile;
    }

    /** @return pont PlaceholderAPI (no-op si absent) */
    public PlaceholderBridge placeholders() {
        return placeholders;
    }

    /**
     * Résout les placeholders d'une entrée de condition pour ce joueur (mémorisé pour la durée du contexte).
     *
     * @param input texte brut issu de la configuration
     * @return texte résolu (inchangé sans PlaceholderAPI)
     */
    public String resolve(@Nullable String input) {
        if (input == null) {
            return "";
        }
        if (input.indexOf('%') < 0) {
            return input;
        }
        Map<String, String> memo = resolved;
        if (memo == null) {
            memo = new HashMap<>(8);
            resolved = memo;
        }
        String value = memo.get(input);
        if (value == null) {
            value = placeholders.apply(player, input);
            memo.put(input, value);
        }
        return value;
    }
}
