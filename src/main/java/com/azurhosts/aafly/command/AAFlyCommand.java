package com.azurhosts.aafly.command;

import com.azurhosts.aafly.AAFlyPlugin;
import com.azurhosts.aafly.config.ConfigException;
import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.api.DisableCause;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.FlightService;
import com.azurhosts.aafly.core.FlightTicker;
import com.azurhosts.aafly.core.FlySpeedService;
import com.azurhosts.aafly.core.TimeChangeResult;
import com.azurhosts.aafly.core.ToggleResult;
import com.azurhosts.aafly.token.TokenResult;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /aafly} : administration (SPEC §8). Console autorisée. Chaque sous-commande vérifie sa permission :
 * <ul>
 *   <li>{@code add|remove|set <joueur> <temps> [-s]}, {@code reset <joueur> [-s]} : {@code aafly.admin.time}</li>
 *   <li>{@code addall|removeall <temps> [-s]} : {@code aafly.admin.time.all}</li>
 *   <li>{@code activate|deactivate|toggle <joueur> [-s]} : {@code aafly.admin.toggle}</li>
 *   <li>{@code status <joueur>} : {@code aafly.admin.status}</li>
 *   <li>{@code convert <joueur> <temps>} : {@code aafly.admin.token}</li>
 *   <li>{@code reload} : {@code aafly.admin.reload} ; {@code about} : {@code aafly.admin.about} ;
 *       {@code help} : {@code aafly.admin}</li>
 * </ul>
 * Un joueur hors-ligne est accepté pour les opérations de temps et {@code status} s'il est déjà connu.
 * Quand {@code timed-fly.enabled} est vrai, add/remove/set/addall/removeall sont refusés.
 *
 * <p>AAFly par AzurHOSTS.
 */
public final class AAFlyCommand implements BasicCommand {

    /** Permission de l'aide administrateur. */
    public static final String PERM_ADMIN = "aafly.admin";
    /** Permission add/remove/set/reset. */
    public static final String PERM_TIME = "aafly.admin.time";
    /** Permission addall/removeall. */
    public static final String PERM_TIME_ALL = "aafly.admin.time.all";
    /** Permission activate/deactivate/toggle. */
    public static final String PERM_TOGGLE = "aafly.admin.toggle";
    /** Permission status. */
    public static final String PERM_STATUS = "aafly.admin.status";
    /** Permission convert (jeton sans débit). */
    public static final String PERM_TOKEN = "aafly.admin.token";
    /** Permission reload. */
    public static final String PERM_RELOAD = "aafly.admin.reload";
    /** Permission about. */
    public static final String PERM_ABOUT = "aafly.admin.about";

    private static final String LABEL = "aafly";
    private static final int MAX_ERROR_LENGTH = 300;

    private static final List<String> ALL_PERMISSIONS = List.of(PERM_ADMIN, PERM_TIME, PERM_TIME_ALL, PERM_TOGGLE,
            PERM_STATUS, PERM_TOKEN, PERM_RELOAD, PERM_ABOUT);

    /** Opérations de temps sur un joueur. */
    private enum TimeOp { ADD, REMOVE, SET, RESET }

    private final AAFlyPlugin plugin;
    /** {@code /aafly migrate} (SPEC-phase2 A9) : console uniquement, {@code aafly.admin.migrate}. */
    private final com.azurhosts.aafly.migration.MigrateCommand migrate;

    /**
     * @param plugin plugin (services résolus à l'exécution)
     */
    public AAFlyCommand(AAFlyPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.migrate = new com.azurhosts.aafly.migration.MigrateCommand(plugin);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String[] a = CommandUtil.safeArgs(args);
        try {
            dispatch(sender, a);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "/aafly en erreur", e);
            plugin.lang().send(sender, "errors.storage");
        }
    }

    private void dispatch(CommandSender sender, String[] args) {
        if (args.length == 0) {
            help(sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add", "addtime" -> modify(sender, TimeOp.ADD, args);
            case "remove", "removetime" -> modify(sender, TimeOp.REMOVE, args);
            case "set", "settime" -> modify(sender, TimeOp.SET, args);
            case "reset" -> modify(sender, TimeOp.RESET, args);
            case "addall" -> modifyAll(sender, true, args);
            case "removeall" -> modifyAll(sender, false, args);
            case "activate" -> toggle(sender, args, Boolean.TRUE);
            case "deactivate" -> toggle(sender, args, Boolean.FALSE);
            case "toggle" -> toggle(sender, args, null);
            case "status" -> status(sender, args);
            case "convert" -> convert(sender, args);
            case "reload" -> reload(sender);
            case "about", "version" -> about(sender);
            case "help", "aide", "?" -> help(sender);
            case "migrate" -> migrate.execute(sender, args);
            default -> plugin.lang().send(sender, "errors.unknown-subcommand", Lang.unparsed("label", LABEL));
        }
    }

    private void usage(CommandSender sender, String usage) {
        plugin.lang().send(sender, "errors.usage", Lang.unparsed("usage", usage));
    }

    // ------------------------------------------------------------------ add / remove / set / reset

    private void modify(CommandSender sender, TimeOp op, String[] args) {
        if (!CommandUtil.checkPermission(plugin, sender, PERM_TIME)) {
            return;
        }
        Lang lang = plugin.lang();
        if (op != TimeOp.RESET && plugin.timedFly().enabled()) {
            lang.send(sender, "errors.timed-fly-locked");
            return;
        }
        CommandUtil.Parsed p = CommandUtil.parseFlags(args, 1);
        String sub = args[0].toLowerCase(Locale.ROOT);
        int expected = op == TimeOp.RESET ? 1 : 2;
        if (p.size() != expected) {
            usage(sender, op == TimeOp.RESET ? "/aafly reset <joueur> [-s]" : "/aafly " + sub + " <joueur> <temps> [-s]");
            return;
        }
        String name = Objects.requireNonNull(p.arg(0));
        if (!CommandUtil.isValidName(name)) {
            lang.send(sender, "errors.invalid-player-name");
            return;
        }
        long amount = 0L;
        if (op != TimeOp.RESET) {
            OptionalLong parsed = CommandUtil.parseTime(plugin, sender, p.arg(1), 1L,
                    plugin.settings().limits().maxSeconds());
            if (parsed.isEmpty()) {
                return;
            }
            amount = parsed.getAsLong();
        }
        long value = amount;
        boolean silent = p.silent();
        CommandUtil.onMain(plugin, sender, CommandUtil.resolve(plugin, sender, name, false), opt -> {
            if (opt.isEmpty()) {
                lang.send(sender, "admin.unknown-player", CommandUtil.player(name));
                return;
            }
            CommandUtil.Target target = opt.get();
            FlightService flight = plugin.flightService();
            CompletableFuture<TimeChangeResult> future = CommandUtil.call(() -> switch (op) {
                case ADD -> flight.addTime(target.uuid(), value, sender);
                case REMOVE -> flight.removeTime(target.uuid(), value, sender);
                case SET -> flight.setTime(target.uuid(), value, sender);
                case RESET -> flight.reset(target.uuid(), sender);
            });
            CommandUtil.onMain(plugin, sender, future, result -> reportTimeChange(sender, op, target, result, silent));
        });
    }

    private void reportTimeChange(CommandSender sender, TimeOp op, CommandUtil.Target target, TimeChangeResult result,
                                  boolean silent) {
        if (!result.success()) {
            CommandUtil.sendTimeChangeFailure(plugin, sender, result, target.name());
            return;
        }
        Lang lang = plugin.lang();
        long oldS = result.oldSeconds();
        long newS = result.newSeconds();
        var who = CommandUtil.player(target.name());
        var total = Lang.component("total", lang.parse(plugin.timeFormatter().format(newS)));
        Player online = Bukkit.getPlayer(target.uuid());
        switch (op) {
            case ADD -> {
                long delta = Math.max(0L, newS - oldS);
                lang.send(sender, "admin.added", CommandUtil.time(plugin, delta), who, total);
                if (!silent && online != null) {
                    lang.send(online, "target.added", CommandUtil.time(plugin, delta));
                }
            }
            case REMOVE -> {
                long delta = Math.max(0L, oldS - newS);
                lang.send(sender, "admin.removed", CommandUtil.time(plugin, delta), who, total);
                if (!silent && online != null) {
                    lang.send(online, "target.removed", CommandUtil.time(plugin, delta));
                }
            }
            case SET -> {
                lang.send(sender, "admin.set", CommandUtil.time(plugin, newS), who);
                if (!silent && online != null) {
                    lang.send(online, "target.set", CommandUtil.time(plugin, newS));
                }
            }
            case RESET -> {
                lang.send(sender, "admin.reset", who);
                if (!silent && online != null) {
                    lang.send(online, "target.reset");
                }
            }
        }
    }

    // ------------------------------------------------------------------ addall / removeall

    private void modifyAll(CommandSender sender, boolean add, String[] args) {
        if (!CommandUtil.checkPermission(plugin, sender, PERM_TIME_ALL)) {
            return;
        }
        Lang lang = plugin.lang();
        if (plugin.timedFly().enabled()) {
            lang.send(sender, "errors.timed-fly-locked");
            return;
        }
        CommandUtil.Parsed p = CommandUtil.parseFlags(args, 1);
        if (p.size() != 1) {
            usage(sender, "/aafly " + (add ? "addall" : "removeall") + " <temps> [-s]");
            return;
        }
        OptionalLong parsed = CommandUtil.parseTime(plugin, sender, p.arg(0), 1L,
                plugin.settings().limits().maxSeconds());
        if (parsed.isEmpty()) {
            return;
        }
        long amount = parsed.getAsLong();
        FlightService flight = plugin.flightService();
        int count = add ? flight.addAll(amount, sender) : flight.removeAll(amount, sender);
        lang.send(sender, add ? "admin.added-all" : "admin.removed-all", CommandUtil.time(plugin, amount),
                Lang.unparsed("count", Integer.toString(Math.max(0, count))));
        if (!p.silent() && count > 0) {
            String key = add ? "target.added" : "target.removed";
            for (Player online : Bukkit.getOnlinePlayers()) {
                // Seuls les joueurs dont le profil est chargé et qui ne sont pas illimités sont concernés.
                if (flight.profile(online.getUniqueId()) != null && !flight.hasUnlimited(online)) {
                    lang.send(online, key, CommandUtil.time(plugin, amount));
                }
            }
        }
    }

    // ------------------------------------------------------------------ activate / deactivate / toggle

    private void toggle(CommandSender sender, String[] args, @Nullable Boolean wanted) {
        if (!CommandUtil.checkPermission(plugin, sender, PERM_TOGGLE)) {
            return;
        }
        Lang lang = plugin.lang();
        CommandUtil.Parsed p = CommandUtil.parseFlags(args, 1);
        if (p.size() != 1) {
            usage(sender, "/aafly " + args[0].toLowerCase(Locale.ROOT) + " <joueur> [-s]");
            return;
        }
        String name = Objects.requireNonNull(p.arg(0));
        if (!CommandUtil.isValidName(name)) {
            lang.send(sender, "errors.invalid-player-name");
            return;
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            lang.send(sender, "errors.player-not-found", CommandUtil.player(name));
            return;
        }
        FlightService flight = plugin.flightService();
        boolean enable = wanted != null ? wanted : !flight.isEnabled(target);
        // Les messages d'état et actions on-enable/on-disable sont envoyés au joueur par FlightService (sauf -s).
        ToggleResult result = enable
                ? flight.enable(target, DisableCause.ADMIN, p.silent())
                : flight.disable(target, DisableCause.ADMIN, p.silent());
        var who = CommandUtil.player(target.getName());
        if (result.changed()) {
            lang.send(sender, result == ToggleResult.ENABLED ? "admin.activated" : "admin.deactivated", who);
            if (!p.silent() && sender != target && target.isOnline()) {
                lang.send(target, result == ToggleResult.ENABLED ? "target.activated" : "target.deactivated");
            }
        } else {
            lang.send(sender, "admin.activate-failed", who, Lang.unparsed("reason", CommandUtil.reason(result)));
        }
    }

    // ------------------------------------------------------------------ status

    private void status(CommandSender sender, String[] args) {
        if (!CommandUtil.checkPermission(plugin, sender, PERM_STATUS)) {
            return;
        }
        Lang lang = plugin.lang();
        if (args.length != 2) {
            usage(sender, "/aafly status <joueur>");
            return;
        }
        String name = args[1];
        if (!CommandUtil.isValidName(name)) {
            lang.send(sender, "errors.invalid-player-name");
            return;
        }
        CommandUtil.onMain(plugin, sender, CommandUtil.resolve(plugin, sender, name, false), opt -> {
            if (opt.isEmpty()) {
                lang.send(sender, "admin.unknown-player", CommandUtil.player(name));
                return;
            }
            CommandUtil.Target t = opt.get();
            FlightProfile profile = t.profile();
            if (profile == null) {
                lang.send(sender, "errors.profile-loading");
                return;
            }
            FlightService flight = plugin.flightService();
            Player online = t.online();
            boolean unlimited = online != null && flight.hasUnlimited(online);
            String unlimitedText = online == null ? "inconnu (hors ligne)" : yesNo(unlimited);
            boolean enabled = online != null ? flight.isEnabled(online) : profile.flightEnabled();
            String enabledText = yesNo(enabled) + (online == null || enabled == profile.flightEnabled() ? ""
                    : " (voulu : " + yesNo(profile.flightEnabled()) + ")");
            int level = FlySpeedService.speedToLevel(profile.flySpeed());
            lang.send(sender, "admin.status",
                    CommandUtil.player(t.name()),
                    unlimited ? CommandUtil.unlimitedTime(plugin) : CommandUtil.time(plugin, profile.remainingSeconds()),
                    Lang.unparsed("enabled", enabledText),
                    Lang.unparsed("flying", online == null ? "non (hors ligne)" : yesNo(online.isFlying())),
                    Lang.unparsed("unlimited", unlimitedText),
                    Lang.unparsed("speed", level + " (" + String.format(Locale.ROOT, "%.1f", profile.flySpeed()) + ")"),
                    Lang.unparsed("suspended", online == null ? "non" : yesNo(flight.isSuspendedByRule(t.uuid()))));
        });
    }

    private static String yesNo(boolean value) {
        return value ? "oui" : "non";
    }

    // ------------------------------------------------------------------ convert

    private void convert(CommandSender sender, String[] args) {
        if (!CommandUtil.checkPermission(plugin, sender, PERM_TOKEN)) {
            return;
        }
        Lang lang = plugin.lang();
        if (!plugin.settings().token().enabled()) {
            lang.send(sender, "errors.feature-disabled");
            return;
        }
        if (args.length != 3) {
            usage(sender, "/aafly convert <joueur> <temps>");
            return;
        }
        String name = args[1];
        if (!CommandUtil.isValidName(name)) {
            lang.send(sender, "errors.invalid-player-name");
            return;
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            lang.send(sender, "errors.player-not-found", CommandUtil.player(name));
            return;
        }
        OptionalLong parsed = CommandUtil.parseTime(plugin, sender, args[2], 1L, plugin.settings().limits().maxSeconds());
        if (parsed.isEmpty()) {
            return;
        }
        long seconds = parsed.getAsLong();
        UUID targetId = target.getUniqueId();
        String targetName = target.getName();
        CommandUtil.onMain(plugin, sender, CommandUtil.call(() -> plugin.tokenService().createAdmin(sender, target, seconds)),
                result -> {
                    var who = CommandUtil.player(targetName);
                    switch (result) {
                        case SUCCESS -> lang.send(sender, "token.created-admin", CommandUtil.time(plugin, seconds), who);
                        case SUCCESS_DROPPED -> {
                            lang.send(sender, "token.created-admin", CommandUtil.time(plugin, seconds), who);
                            Player now = Bukkit.getPlayer(targetId);
                            if (now != null) {
                                lang.send(now, "token.inventory-full");
                            }
                        }
                        case DISABLED -> lang.send(sender, "errors.feature-disabled");
                        case INVALID_AMOUNT -> CommandUtil.sendBounds(plugin, sender, 1L,
                                plugin.settings().limits().maxSeconds());
                        case TIMED_FLY_LOCKED -> lang.send(sender, "errors.timed-fly-locked");
                        case INSUFFICIENT_TIME, UNLIMITED, STORAGE_ERROR -> lang.send(sender, "token.error");
                    }
                });
    }

    // ------------------------------------------------------------------ reload / about / help

    private void reload(CommandSender sender) {
        if (!CommandUtil.checkPermission(plugin, sender, PERM_RELOAD)) {
            return;
        }
        try {
            plugin.reload();
        } catch (ConfigException e) {
            plugin.getLogger().warning("Rechargement refusé : " + e.getMessage());
            plugin.lang().send(sender, "admin.reload-failed", Lang.unparsed("error", truncate(e.getMessage())));
            return;
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Rechargement en erreur", e);
            plugin.lang().send(sender, "admin.reload-failed",
                    Lang.unparsed("error", truncate(e.getClass().getSimpleName())));
            return;
        }
        plugin.lang().send(sender, "admin.reload-success");
        plugin.lang().send(sender, "admin.reload-storage-note");
    }

    private static String truncate(@Nullable String s) {
        if (s == null) {
            return "";
        }
        String v = s.replaceAll("[\\p{Cntrl}]", " ");
        return v.length() > MAX_ERROR_LENGTH ? v.substring(0, MAX_ERROR_LENGTH) + "…" : v;
    }

    private void about(CommandSender sender) {
        if (!CommandUtil.checkPermission(plugin, sender, PERM_ABOUT)) {
            return;
        }
        String storage;
        try {
            storage = plugin.storage().type().name();
        } catch (IllegalStateException e) {
            storage = "non initialisé";
        }
        String ticker;
        try {
            FlightTicker t = plugin.ticker();
            FlightTicker.Stats s = t.stats();
            ticker = (t.running() ? "actif" : "arrêté") + ", " + s.trackedPlayers() + " joueur(s) suivi(s), "
                    + s.ticks() + " passage(s), moy. "
                    + String.format(Locale.ROOT, "%.3f", s.averageTickNanos() / 1_000_000.0D) + " ms";
        } catch (IllegalStateException e) {
            ticker = "non initialisé";
        }
        plugin.lang().send(sender, "admin.about",
                Lang.unparsed("version", plugin.getPluginMeta().getVersion()),
                Lang.unparsed("storage", storage),
                Lang.unparsed("ticker", ticker));
        // SPEC-phase2 B3 : clés distinctes de la liste admin.about, pour qu'elles soient ajoutées automatiquement
        // aux messages.yml existants (addMissing ne fusionne pas les listes).
        plugin.lang().send(sender, "admin.about-api", Lang.unparsed("api", aboutApi()));
        plugin.lang().send(sender, "admin.about-integrations", Lang.unparsed("integrations", aboutIntegrations()));
    }

    /** État de l'API publique (SPEC-phase2 B3) : version, crédits externes, extensions, vols possédés. */
    private String aboutApi() {
        try {
            com.azurhosts.aafly.config.Settings s = plugin.settings();
            return "v" + com.azurhosts.aafly.api.ApiVersion.CURRENT + ", crédits externes "
                    + (s.api().allowExternalGrants() ? "autorisés" : "refusés") + ", "
                    + plugin.threatResolverCount() + " résolveur(s) de menace tiers, "
                    + plugin.ownedFlightCount() + " vol(s) accordé(s) par AAFly";
        } catch (IllegalStateException e) {
            return "non initialisée";
        }
    }

    /** Intégrations détectées (PlaceholderAPI, LuckPerms, AAItems). */
    private String aboutIntegrations() {
        org.bukkit.plugin.PluginManager pm = plugin.getServer().getPluginManager();
        StringBuilder b = new StringBuilder();
        b.append("PlaceholderAPI ").append(pm.isPluginEnabled("PlaceholderAPI") ? "actif" : "absent");
        b.append(", LuckPerms ").append(pm.isPluginEnabled("LuckPerms") ? "actif" : "absent");
        b.append(", AAItems ");
        if (!pm.isPluginEnabled("AAItems")) {
            b.append("absent");
        } else if (!plugin.settings().integrations().aaitems().enabled()) {
            b.append("actif (affichage de la délégation désactivé)");
        } else {
            b.append(plugin.settings().api().allowExternalGrants()
                    ? "actif, délégation du vol possible (fournisseur aafly)"
                    : "actif, délégation refusée (api.allow-external-grants: false)");
        }
        return b.toString();
    }

    private void help(CommandSender sender) {
        if (!CommandUtil.checkPermission(plugin, sender, PERM_ADMIN)) {
            return;
        }
        plugin.lang().send(sender, "help.admin");
    }

    // ------------------------------------------------------------------ auto-complétion

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String[] a = CommandUtil.completionArgs(args);
        if (a.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission(PERM_TIME)) {
                subs.addAll(List.of("add", "remove", "set", "reset"));
            }
            if (sender.hasPermission(PERM_TIME_ALL)) {
                subs.addAll(List.of("addall", "removeall"));
            }
            if (sender.hasPermission(PERM_TOGGLE)) {
                subs.addAll(List.of("activate", "deactivate", "toggle"));
            }
            if (sender.hasPermission(PERM_STATUS)) {
                subs.add("status");
            }
            if (sender.hasPermission(PERM_TOKEN)) {
                subs.add("convert");
            }
            if (sender.hasPermission(PERM_RELOAD)) {
                subs.add("reload");
            }
            if (sender.hasPermission(PERM_ABOUT)) {
                subs.add("about");
            }
            if (sender.hasPermission(PERM_ADMIN)) {
                subs.add("help");
            }
            if (!(sender instanceof Player) && sender.hasPermission(com.azurhosts.aafly.migration.MigrateCommand.PERMISSION)) {
                subs.add("migrate");
            }
            return CommandUtil.filter(subs, a[0]);
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("migrate")) {
            return migrate.suggest(sender, a);
        }
        String last = a[a.length - 1];
        // Position de l'argument en ignorant les -s déjà saisis.
        int pos = 0;
        for (int i = 1; i < a.length - 1; i++) {
            if (!CommandUtil.FLAG_SILENT.equalsIgnoreCase(a[i])) {
                pos++;
            }
        }
        return switch (sub) {
            case "add", "addtime", "remove", "removetime", "set", "settime" -> {
                if (!sender.hasPermission(PERM_TIME)) {
                    yield List.of();
                }
                if (pos == 0) {
                    yield CommandUtil.withSilentFlag(CommandUtil.playerNames(sender, last, false), last, a);
                }
                if (pos == 1) {
                    yield CommandUtil.withSilentFlag(CommandUtil.times(last), last, a);
                }
                yield CommandUtil.withSilentFlag(List.of(), last, a);
            }
            case "reset" -> {
                if (!sender.hasPermission(PERM_TIME)) {
                    yield List.of();
                }
                yield CommandUtil.withSilentFlag(pos == 0 ? CommandUtil.playerNames(sender, last, false) : List.of(),
                        last, a);
            }
            case "addall", "removeall" -> {
                if (!sender.hasPermission(PERM_TIME_ALL)) {
                    yield List.of();
                }
                yield CommandUtil.withSilentFlag(pos == 0 ? CommandUtil.times(last) : List.of(), last, a);
            }
            case "activate", "deactivate", "toggle" -> {
                if (!sender.hasPermission(PERM_TOGGLE)) {
                    yield List.of();
                }
                yield CommandUtil.withSilentFlag(pos == 0 ? CommandUtil.playerNames(sender, last, false) : List.of(),
                        last, a);
            }
            case "status" -> sender.hasPermission(PERM_STATUS) && a.length == 2
                    ? CommandUtil.playerNames(sender, last, false) : List.of();
            case "convert" -> {
                if (!sender.hasPermission(PERM_TOKEN)) {
                    yield List.of();
                }
                if (a.length == 2) {
                    yield CommandUtil.playerNames(sender, last, false);
                }
                yield a.length == 3 ? CommandUtil.times(last) : List.of();
            }
            default -> List.of();
        };
    }

    @Override
    public boolean canUse(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return true;
        }
        for (String perm : ALL_PERMISSIONS) {
            if (sender.hasPermission(perm)) {
                return true;
            }
        }
        return false;
    }
}
