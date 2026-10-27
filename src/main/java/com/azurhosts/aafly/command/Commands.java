package com.azurhosts.aafly.command;

import com.azurhosts.aafly.AAFlyPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enregistrement des commandes AAFly via l'API Brigadier de Paper ({@link LifecycleEvents#COMMANDS},
 * {@link BasicCommand}) : aucune déclaration dans {@code paper-plugin.yml} n'est nécessaire.
 *
 * <ul>
 *   <li>{@code /fly} (+ alias {@code commands.fly-aliases}), {@code /flyspeed}, {@code /flytime}, {@code /aafly}.</li>
 *   <li>Le gestionnaire est rappelé par Paper à chaque rechargement des commandes ({@code /minecraft:reload}) :
 *       les alias sont alors relus depuis la configuration courante.</li>
 *   <li>Les instances de commandes sont créées une seule fois (état du cooldown de {@code give} conservé).</li>
 * </ul>
 *
 * <p>AAFly par AzurHOSTS.
 */
public final class Commands {

    /** Libellé principal de {@code /fly}. */
    public static final String FLY = "fly";
    /** Libellé de {@code /aafly}. */
    public static final String AAFLY = "aafly";
    /** Libellé de {@code /flyspeed}. */
    public static final String FLYSPEED = "flyspeed";
    /** Libellé de {@code /flytime}. */
    public static final String FLYTIME = "flytime";

    private static final Set<String> RESERVED = Set.of(FLY, AAFLY, FLYSPEED, FLYTIME);
    private static final Pattern ALIAS = Pattern.compile("^[a-z0-9_]{1,32}$");

    private Commands() {
    }

    /**
     * Commandes créées par {@link #registerAll(AAFlyPlugin)}.
     *
     * @param fly      {@code /fly}
     * @param aafly    {@code /aafly}
     * @param flySpeed {@code /flyspeed}
     * @param flyTime  {@code /flytime}
     */
    public record Registered(FlyCommand fly, AAFlyCommand aafly, FlySpeedCommand flySpeed, FlyTimeCommand flyTime) {
    }

    /**
     * Enregistre toutes les commandes. À appeler <b>une seule fois</b> depuis {@code onEnable()}, après
     * le chargement de la configuration (les services sont résolus via le plugin au moment de l'exécution).
     *
     * @param plugin plugin
     * @return instances créées (ex. pour appeler {@link FlyCommand#forget(java.util.UUID)} à la déconnexion)
     */
    public static Registered registerAll(AAFlyPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        FlyTimeCommand flyTime = new FlyTimeCommand(plugin);
        FlyCommand fly = new FlyCommand(plugin, flyTime);
        FlySpeedCommand flySpeed = new FlySpeedCommand(plugin);
        AAFlyCommand aafly = new AAFlyCommand(plugin);

        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            io.papermc.paper.command.brigadier.Commands registrar = event.registrar();
            List<String> aliases = flyAliases(plugin);
            check(plugin, FLY, aliases, registrar.register(FLY,
                    "Active ou désactive le vol temporisé (AAFly par AzurHOSTS).", aliases, fly));
            check(plugin, AAFLY, List.of(), registrar.register(AAFLY,
                    "Administration d'AAFly (AzurHOSTS).", List.of(), aafly));
            check(plugin, FLYSPEED, List.of(), registrar.register(FLYSPEED,
                    "Règle la vitesse de vol (1-10).", List.of(), flySpeed));
            check(plugin, FLYTIME, List.of(), registrar.register(FLYTIME,
                    "Affiche le temps de vol restant.", List.of(), flyTime));
        });
        return new Registered(fly, aafly, flySpeed, flyTime);
    }

    /**
     * Alias de {@code /fly} : validés, dédoublonnés, sans libellé réservé.
     */
    static List<String> flyAliases(AAFlyPlugin plugin) {
        List<String> configured;
        try {
            configured = plugin.settings().commands().flyAliases();
        } catch (IllegalStateException e) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String raw : configured) {
            if (raw == null) {
                continue;
            }
            String alias = raw.trim().toLowerCase(Locale.ROOT);
            if (!ALIAS.matcher(alias).matches()) {
                plugin.getLogger().warning("commands.fly-aliases : alias ignoré (invalide).");
                continue;
            }
            if (RESERVED.contains(alias)) {
                continue;
            }
            out.add(alias);
        }
        return new ArrayList<>(out);
    }

    private static void check(AAFlyPlugin plugin, String label, List<String> aliases, Set<String> registered) {
        if (!registered.contains(label)) {
            plugin.getLogger().warning("/" + label + " est déjà prise par un autre plugin : utilisez /"
                    + AAFLY + ":" + label + ".");
        }
        for (String alias : aliases) {
            if (!registered.contains(alias)) {
                plugin.getLogger().warning("Alias /" + alias + " déjà pris par un autre plugin, ignoré.");
            }
        }
    }
}
