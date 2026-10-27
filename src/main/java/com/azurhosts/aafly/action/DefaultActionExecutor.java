package com.azurhosts.aafly.action;

import com.azurhosts.aafly.config.MessagesConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import org.bukkit.entity.Player;

/**
 * Exécution des actions sur le main thread. Les variables {@code player} et {@code uuid} sont toujours
 * renseignées à partir du joueur (elles écrasent celles de l'appelant) ; {@code remaining} est fournie
 * par l'appelant ou, à défaut, par {@link ActionRuntime#remaining(Player)}.
 *
 * <p>Les listes nommées de {@code messages.yml} ({@code on-enable}, {@code on-disable}, {@code on-expire},
 * {@code not-allowed-here}…) sont analysées une fois puis mises en cache jusqu'au prochain
 * {@link #reload(MessagesConfig)}. Une action en erreur est journalisée et n'interrompt pas les suivantes.
 */
public final class DefaultActionExecutor implements ActionExecutor {

    private final ActionParser parser;
    private final ActionRuntime runtime;
    private final ConcurrentMap<String, List<Action>> named = new ConcurrentHashMap<>();
    private volatile MessagesConfig messages;

    /**
     * @param parser   analyseur (listes nommées)
     * @param runtime  services d'exécution
     * @param messages messages validés
     */
    public DefaultActionExecutor(ActionParser parser, ActionRuntime runtime, MessagesConfig messages) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * Remplace les messages et oublie les listes nommées déjà analysées.
     * À appeler après {@link DefaultActionParser#reload(com.azurhosts.aafly.config.Settings)}.
     *
     * @param newMessages messages validés
     */
    public void reload(MessagesConfig newMessages) {
        this.messages = Objects.requireNonNull(newMessages, "messages");
        named.clear();
    }

    @Override
    public void run(Player player, List<Action> actions, Map<String, String> vars) {
        if (player == null || actions == null || actions.isEmpty()) {
            return;
        }
        if (!runtime.isPrimaryThread()) {
            // Garde-fou : l'API Bukkit n'est jamais touchée hors du main thread.
            List<Action> copy = List.copyOf(actions);
            Map<String, String> varsCopy = vars == null ? Map.of() : new HashMap<>(vars);
            runtime.runOnMain(() -> {
                if (player.isOnline()) {
                    run(player, copy, varsCopy);
                }
            });
            return;
        }
        Map<String, String> all = new HashMap<>();
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    all.put(e.getKey(), e.getValue());
                }
            }
        }
        all.put(Action.VAR_PLAYER, player.getName());
        all.put(Action.VAR_UUID, player.getUniqueId().toString());
        if (!all.containsKey(Action.VAR_REMAINING) && needsRemaining(actions)) {
            String remaining = runtime.remaining(player);
            all.put(Action.VAR_REMAINING, remaining == null ? "" : remaining);
        }
        Map<String, String> frozen = Map.copyOf(all);
        for (Action action : actions) {
            try {
                action.execute(player, frozen);
            } catch (RuntimeException e) {
                runtime.logger().log(Level.WARNING, "Erreur pendant l'exécution d'une action pour "
                        + player.getName() + " : " + e.getClass().getSimpleName(), e);
            }
        }
    }

    private static boolean needsRemaining(List<Action> actions) {
        for (Action a : actions) {
            if (!(a instanceof ConfiguredAction ca)) {
                return true; // action tierce : on fournit toutes les variables
            }
            if (ca.raw().contains("%" + Action.VAR_REMAINING + "%")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void runNamed(Player player, String key, Map<String, String> vars) {
        if (key == null) {
            return;
        }
        MessagesConfig m = messages;
        List<Action> actions = named.computeIfAbsent(key, k -> parser.parseAll(m.list(k), "messages.yml:" + k));
        run(player, actions, vars);
    }

    /**
     * @return analyseur utilisé
     */
    public ActionParser parser() {
        return parser;
    }
}
