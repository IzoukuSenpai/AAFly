package com.azurhosts.aafly.action;

import com.azurhosts.aafly.config.MessagesConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongFunction;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code commands-time-remaining} : déclenche les actions d'un seuil quand le temps restant le franchit
 * en descendant ({@code précédent > seuil ≥ actuel}), une seule fois par passage. Si plusieurs seuils
 * sont franchis d'un coup (retrait de temps), seul le plus proche du temps actuel est déclenché.
 * Le premier relevé d'un joueur ne déclenche que s'il tombe exactement sur un seuil.
 * Main thread uniquement.
 */
public final class DefaultTimeThresholdNotifier implements TimeThresholdNotifier {

    private final ActionParser parser;
    private final ActionExecutor executor;
    private final LongFunction<String> formatter;
    private final Map<UUID, Long> lastSeen = new HashMap<>();
    private volatile NavigableMap<Long, List<Action>> thresholds = Collections.emptyNavigableMap();

    /**
     * @param parser    analyseur des actions
     * @param executor  exécuteur
     * @param formatter formatage du temps restant pour {@code %remaining%} (ex. {@code TimeFormatter::format})
     * @param messages  messages validés (seuils)
     */
    public DefaultTimeThresholdNotifier(ActionParser parser, ActionExecutor executor, LongFunction<String> formatter,
                                        MessagesConfig messages) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        reload(messages);
    }

    @Override
    public void onTick(Player player, long remainingSeconds) {
        NavigableMap<Long, List<Action>> t = thresholds;
        UUID uuid = player.getUniqueId();
        Long previous = lastSeen.put(uuid, remainingSeconds);
        if (t.isEmpty()) {
            return;
        }
        long prev = previous == null ? saturatedIncrement(remainingSeconds) : previous;
        Long threshold = crossed(t, prev, remainingSeconds);
        if (threshold == null) {
            return;
        }
        List<Action> actions = t.get(threshold);
        if (actions == null || actions.isEmpty()) {
            return;
        }
        executor.run(player, actions, Map.of(Action.VAR_REMAINING, formatter.apply(remainingSeconds),
                Action.VAR_REMAINING_SECONDS, Long.toString(Math.max(0L, remainingSeconds))));
    }

    /**
     * Seuil franchi (pur, testable).
     *
     * @param thresholds seuils triés
     * @param previous   relevé précédent
     * @param current    relevé actuel
     * @return seuil {@code t} le plus bas tel que {@code previous > t >= current}, ou {@code null}
     */
    public static @Nullable Long crossed(NavigableMap<Long, ?> thresholds, long previous, long current) {
        if (current >= previous) {
            return null;
        }
        Long t = thresholds.ceilingKey(current);
        return t != null && t < previous ? t : null;
    }

    private static long saturatedIncrement(long v) {
        return v == Long.MAX_VALUE ? v : v + 1L;
    }

    @Override
    public void reset(UUID uuid) {
        lastSeen.remove(uuid);
    }

    @Override
    public void reload(MessagesConfig messages) {
        Objects.requireNonNull(messages, "messages");
        NavigableMap<Long, List<Action>> parsed = new TreeMap<>();
        for (Map.Entry<Long, List<String>> e : messages.timeRemaining().entrySet()) {
            List<Action> actions = parser.parseAll(e.getValue(),
                    MessagesConfig.KEY_TIME_REMAINING + "." + e.getKey());
            if (!actions.isEmpty()) {
                parsed.put(e.getKey(), actions);
            }
        }
        this.thresholds = Collections.unmodifiableNavigableMap(parsed);
    }

    /**
     * @return seuils chargés (secondes)
     */
    public java.util.NavigableSet<Long> thresholds() {
        return thresholds.navigableKeySet();
    }
}
