package com.azurhosts.aafly.action;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Actions produites par {@link DefaultActionParser}. Immuables ; l'exécution (main thread) ne lève
 * jamais d'exception pour une donnée invalide : l'action est ignorée et un WARN (dédupliqué) est émis.
 */
public sealed interface ConfiguredAction extends Action
        permits ConfiguredAction.TextAction, ConfiguredAction.TitleAction,
        ConfiguredAction.CommandAction, ConfiguredAction.SoundAction {

    /**
     * @return type de l'action
     */
    ActionType type();

    /**
     * @return texte de configuration d'origine
     */
    String raw();

    /**
     * Résolution PlaceholderAPI d'un jeton pour un joueur.
     *
     * @param runtime services
     * @param player  joueur
     * @return opérateur de résolution
     */
    private static UnaryOperator<String> papi(ActionRuntime runtime, Player player) {
        return token -> runtime.placeholders().apply(player, token);
    }

    private static void skipped(ActionRuntime runtime, String origin, String raw, ActionSkippedException e) {
        runtime.warnOnce(origin + '\u0000' + raw + '\u0000' + e.getMessage(), "[" + DefaultActionParser.shorten(origin)
                + "] Action ignorée « " + DefaultActionParser.shorten(raw) + " » : " + e.getMessage());
    }

    private static @Nullable Component render(ActionRuntime runtime, ActionTemplate template, Player player,
                                              Map<String, String> vars, String origin, String raw) {
        try {
            ActionTemplate.MiniMessageText text = template.renderMiniMessage(vars, papi(runtime, player));
            return runtime.lang().parse(text.text(), text.resolver());
        } catch (ActionSkippedException e) {
            skipped(runtime, origin, raw, e);
            return null;
        }
    }

    /**
     * {@code [message]}, {@code [actionbar]}, {@code [subtitle]}.
     *
     * @param type     MESSAGE, ACTIONBAR ou SUBTITLE
     * @param origin   origine pour les logs (ex. {@code fly-rules.no_combat.actions})
     * @param raw      texte d'origine
     * @param template gabarit du contenu
     * @param runtime  services
     */
    record TextAction(ActionType type, String origin, String raw, ActionTemplate template, ActionRuntime runtime)
            implements ConfiguredAction {

        public TextAction {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(template, "template");
            if (type != ActionType.MESSAGE && type != ActionType.ACTIONBAR && type != ActionType.SUBTITLE) {
                throw new IllegalArgumentException("type texte attendu");
            }
        }

        @Override
        public void execute(Player player, Map<String, String> vars) {
            Component c = render(runtime, template, player, vars, origin, raw);
            if (c == null) {
                return;
            }
            switch (type) {
                case MESSAGE -> player.sendMessage(c);
                case ACTIONBAR -> player.sendActionBar(c);
                default -> player.showTitle(Title.title(Component.empty(), c, Title.DEFAULT_TIMES));
            }
        }
    }

    /**
     * {@code [title] titre;sous-titre}.
     *
     * @param origin   origine pour les logs
     * @param raw      texte d'origine
     * @param title    gabarit du titre
     * @param subtitle gabarit du sous-titre (éventuellement vide)
     * @param runtime  services
     */
    record TitleAction(String origin, String raw,ActionTemplate title, ActionTemplate subtitle, ActionRuntime runtime)
            implements ConfiguredAction {

        /** Durées d'affichage : 0,5 s / 3,5 s / 1 s. */
        public static final Title.Times TIMES =
                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3_500), Duration.ofMillis(1_000));

        @Override
        public ActionType type() {
            return ActionType.TITLE;
        }

        @Override
        public void execute(Player player, Map<String, String> vars) {
            Component t = render(runtime, title, player, vars, origin, raw);
            if (t == null) {
                return;
            }
            Component s = render(runtime, subtitle, player, vars, origin, raw);
            if (s == null) {
                return;
            }
            player.showTitle(Title.title(t, s, TIMES));
        }
    }

    /**
     * {@code [command]} (console) ou {@code [player]} (exécutée par le joueur).
     *
     * @param type     COMMAND ou PLAYER
     * @param origin   origine pour les logs
     * @param raw      texte d'origine
     * @param template gabarit de la commande (sans {@code /})
     * @param runtime  services
     */
    record CommandAction(ActionType type, String origin, String raw, ActionTemplate template, ActionRuntime runtime)
            implements ConfiguredAction {

        public CommandAction {
            if (type != ActionType.COMMAND && type != ActionType.PLAYER) {
                throw new IllegalArgumentException("type commande attendu");
            }
            Objects.requireNonNull(template, "template");
        }

        @Override
        public void execute(Player player, Map<String, String> vars) {
            String command;
            try {
                command = template.renderCommand(vars, papi(runtime, player));
            } catch (ActionSkippedException e) {
                skipped(runtime, origin, raw, e);
                return;
            }
            if (command.isEmpty()) {
                return;
            }
            if (type == ActionType.COMMAND) {
                runtime.dispatchConsole(command);
            } else {
                player.performCommand(command);
            }
        }
    }

    /**
     * {@code [sound] NOM[;volume;pitch]}. Le son est validé et résolu au chargement (SPEC-phase2 A7) :
     * {@code key} est alors renseignée ; la résolution tardive de {@code enumName} ne sert que de repli.
     *
     * @param origin   origine pour les logs
     * @param raw      texte d'origine
     * @param key      clé résolue (forme {@code minecraft:block.note_block.pling}), ou {@code null}
     * @param enumName nom de style enum tel qu'écrit dans la configuration, ou {@code null}
     * @param volume   volume [0, 10]
     * @param pitch    hauteur [0, 2]
     * @param runtime  services
     */
    record SoundAction(String origin, String raw,@Nullable Key key, @Nullable String enumName, float volume, float pitch,
                       ActionRuntime runtime) implements ConfiguredAction {

        public SoundAction {
            if (key == null && enumName == null) {
                throw new IllegalArgumentException("clé ou nom de son requis");
            }
        }

        @Override
        public ActionType type() {
            return ActionType.SOUND;
        }

        @Override
        public void execute(Player player, Map<String, String> vars) {
            Key k = key;
            if (k == null) {
                k = runtime.resolveSoundName(Objects.requireNonNull(enumName)).orElse(null);
                if (k == null) {
                    runtime.warnOnce("sound:" + origin + ':' + enumName, "[" + DefaultActionParser.shorten(origin)
                            + "] Son inconnu « " + DefaultActionParser.shorten(enumName) + " » : action ignorée.");
                    return;
                }
            }
            player.playSound(Sound.sound(k, Sound.Source.MASTER, volume, pitch), Sound.Emitter.self());
        }
    }
}
