package com.azurhosts.aafly.command;

import com.azurhosts.aafly.AAFlyPlugin;
import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.core.FlySpeedService;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /flyspeed <1-10>} : règle la vitesse de vol (permission {@code aafly.speed}, plafond
 * {@code aafly.speed.<n>}). Niveau 1..10 mappé sur 0.1..1.0 par {@link FlySpeedService}. Joueurs uniquement.
 *
 * <p>AAFly par AzurHOSTS.
 */
public final class FlySpeedCommand implements BasicCommand {

    private final AAFlyPlugin plugin;

    /**
     * @param plugin plugin (services résolus à l'exécution)
     */
    public FlySpeedCommand(AAFlyPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        try {
            run(sender, CommandUtil.safeArgs(args));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "/flyspeed en erreur", e);
            plugin.lang().send(sender, "errors.storage");
        }
    }

    private void run(CommandSender sender, String[] args) {
        Player player = CommandUtil.requirePlayer(plugin, sender);
        if (player == null || !CommandUtil.checkPermission(plugin, sender, FlySpeedService.PERM_SPEED)) {
            return;
        }
        Lang lang = plugin.lang();
        FlySpeedService speed = plugin.flySpeed();
        int max = Math.max(FlySpeedService.MIN_LEVEL, Math.min(FlySpeedService.MAX_LEVEL, speed.maxLevel(player)));
        if (args.length != 1) {
            lang.send(sender, "errors.usage", Lang.unparsed("usage", "/flyspeed <1-" + max + ">"));
            return;
        }
        int level = parseLevel(args[0]);
        if (level < FlySpeedService.MIN_LEVEL || level > max) {
            lang.send(sender, "speed.invalid", Lang.unparsed("max", Integer.toString(max)));
            return;
        }
        if (plugin.flightService().profile(player.getUniqueId()) == null) {
            lang.send(sender, "errors.profile-loading");
            return;
        }
        if (!speed.setLevel(player, level)) {
            lang.send(sender, "speed.invalid", Lang.unparsed("max", Integer.toString(max)));
            return;
        }
        lang.send(sender, "speed.set", Lang.unparsed("level", Integer.toString(level)));
    }

    /**
     * Analyse stricte d'un niveau (1 à 2 chiffres ASCII).
     *
     * @param input saisie
     * @return niveau, ou -1 si invalide
     */
    static int parseLevel(@Nullable String input) {
        if (input == null) {
            return -1;
        }
        String s = input.trim();
        if (s.isEmpty() || s.length() > 2) {
            return -1;
        }
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String[] a = CommandUtil.completionArgs(args);
        CommandSender sender = source.getSender();
        if (a.length != 1 || !(sender instanceof Player player) || !sender.hasPermission(FlySpeedService.PERM_SPEED)) {
            return List.of();
        }
        int max;
        try {
            max = plugin.flySpeed().maxLevel(player);
        } catch (IllegalStateException e) {
            return List.of();
        }
        List<String> levels = new ArrayList<>();
        for (int i = FlySpeedService.MIN_LEVEL; i <= Math.min(FlySpeedService.MAX_LEVEL, max); i++) {
            levels.add(Integer.toString(i));
        }
        return CommandUtil.filter(levels, a[0]);
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(FlySpeedService.PERM_SPEED);
    }

    @Override
    public @Nullable String permission() {
        return FlySpeedService.PERM_SPEED;
    }
}
