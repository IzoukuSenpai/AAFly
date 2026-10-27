package com.azurhosts.aafly.command;

import com.azurhosts.aafly.AAFlyPlugin;
import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.core.FlightProfile;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /flytime [joueur]} : affiche le temps de vol restant (permission {@code aafly.time},
 * {@code aafly.time.others} pour un autre joueur). Réutilisé par {@code /fly time}.
 * Réservé aux joueurs (la console utilise {@code /aafly status}).
 *
 * <p>AAFly par AzurHOSTS.
 */
public final class FlyTimeCommand implements BasicCommand {

    /** Permission de consultation de son propre temps. */
    public static final String PERM_TIME = "aafly.time";
    /** Permission de consultation du temps d'un autre joueur. */
    public static final String PERM_TIME_OTHERS = "aafly.time.others";

    private final AAFlyPlugin plugin;

    /**
     * @param plugin plugin (services résolus à l'exécution)
     */
    public FlyTimeCommand(AAFlyPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String[] a = CommandUtil.safeArgs(args);
        try {
            show(sender, a.length > 0 ? a[0] : null);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "/flytime en erreur", e);
            plugin.lang().send(sender, "errors.storage");
        }
    }

    /**
     * Affiche le temps de l'émetteur ({@code targetName == null}) ou d'un autre joueur.
     *
     * @param sender     émetteur (doit être un joueur)
     * @param targetName pseudo saisi, ou {@code null}
     */
    void show(CommandSender sender, @Nullable String targetName) {
        Player self = CommandUtil.requirePlayer(plugin, sender);
        if (self == null || !CommandUtil.checkPermission(plugin, sender, PERM_TIME)) {
            return;
        }
        Lang lang = plugin.lang();
        if (targetName == null || targetName.equalsIgnoreCase(self.getName())) {
            if (plugin.flightService().hasUnlimited(self)) {
                lang.send(self, "time.self-unlimited");
                return;
            }
            FlightProfile profile = plugin.flightService().profile(self.getUniqueId());
            if (profile == null) {
                lang.send(self, "errors.profile-loading");
                return;
            }
            lang.send(self, "time.self", CommandUtil.time(plugin, profile.remainingSeconds()));
            return;
        }
        if (!CommandUtil.checkPermission(plugin, sender, PERM_TIME_OTHERS)) {
            return;
        }
        if (!CommandUtil.isValidName(targetName)) {
            lang.send(sender, "errors.invalid-player-name");
            return;
        }
        CommandUtil.onMain(plugin, sender, CommandUtil.resolve(plugin, sender, targetName), opt -> {
            if (opt.isEmpty()) {
                lang.send(sender, "errors.player-not-found", CommandUtil.player(targetName));
                return;
            }
            CommandUtil.Target t = opt.get();
            if (t.online() != null && plugin.flightService().hasUnlimited(t.online())) {
                lang.send(sender, "time.other-unlimited", CommandUtil.player(t.name()));
                return;
            }
            if (t.profile() == null) {
                lang.send(sender, "errors.profile-loading");
                return;
            }
            lang.send(sender, "time.other", CommandUtil.player(t.name()),
                    CommandUtil.time(plugin, t.profile().remainingSeconds()));
        });
    }

    /**
     * Suggestions pour l'argument {@code [joueur]}.
     *
     * @param sender émetteur
     * @param prefix saisie partielle
     * @return suggestions
     */
    List<String> suggestPlayer(CommandSender sender, String prefix) {
        if (!sender.hasPermission(PERM_TIME_OTHERS)) {
            return List.of();
        }
        return CommandUtil.playerNames(sender, prefix, true);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String[] a = CommandUtil.completionArgs(args);
        if (a.length == 1) {
            return suggestPlayer(source.getSender(), a[0]);
        }
        return List.of();
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(PERM_TIME);
    }

    @Override
    public @Nullable String permission() {
        return PERM_TIME;
    }
}
