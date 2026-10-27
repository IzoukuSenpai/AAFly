package com.azurhosts.aafly.command;

import com.azurhosts.aafly.AAFlyPlugin;
import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.DisableCause;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.FlightProfileSnapshot;
import com.azurhosts.aafly.core.FlightService;
import com.azurhosts.aafly.core.TimeChangeResult;
import com.azurhosts.aafly.core.ToggleResult;
import com.azurhosts.aafly.token.TokenResult;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /fly} (et ses alias {@code commands.fly-aliases}) : {@code toggle} (défaut), {@code help},
 * {@code time [joueur]}, {@code give <joueur> <temps>}, {@code convert <temps>}. Joueurs uniquement :
 * chaque sous-commande vérifie sa propre permission (SPEC §8).
 *
 * <p>État interne (cooldowns de {@code toggle}/{@code give}/{@code convert}, opérations en cours) confiné au main thread.
 *
 * <p>AAFly par AzurHOSTS.
 */
public final class FlyCommand implements BasicCommand {

    /** Permission de {@code /fly} et {@code /fly help}. */
    public static final String PERM_FLY = "aafly.fly";
    /** Permission de {@code /fly give}. */
    public static final String PERM_GIVE = "aafly.give";
    /** Permission de {@code /fly convert}. */
    public static final String PERM_TOKEN = "aafly.token";

    private static final String LABEL = "fly";

    private final AAFlyPlugin plugin;
    private final FlyTimeCommand timeCommand;
    /** Prochain {@code give} autorisé (epoch ms) par donneur. */
    private final Map<UUID, Long> giveCooldowns = new HashMap<>();
    /** Prochain {@code convert} autorisé (epoch ms) par joueur ({@code token.cooldown-seconds}). */
    private final Map<UUID, Long> convertCooldowns = new HashMap<>();
    /** Prochain {@code /fly} (toggle) autorisé (epoch ms) par joueur ({@code commands.toggle-cooldown-millis}). */
    private final Map<UUID, Long> toggleCooldowns = new HashMap<>();
    /** Joueurs ayant une opération de transfert/conversion en cours (anti double-soumission). */
    private final Set<UUID> pending = new HashSet<>();

    /**
     * @param plugin      plugin (services résolus à l'exécution)
     * @param timeCommand implémentation partagée de l'affichage du temps
     */
    public FlyCommand(AAFlyPlugin plugin, FlyTimeCommand timeCommand) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.timeCommand = Objects.requireNonNull(timeCommand, "timeCommand");
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String[] a = CommandUtil.safeArgs(args);
        try {
            dispatch(sender, a);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "/fly en erreur", e);
            plugin.lang().send(sender, "errors.storage");
        }
    }

    private void dispatch(CommandSender sender, String[] args) {
        if (args.length == 0) {
            toggle(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "toggle" -> toggle(sender);
            case "help", "aide", "?" -> help(sender);
            case "time", "temps" -> timeCommand.show(sender, args.length > 1 ? args[1] : null);
            case "give", "donner" -> give(sender, args);
            case "convert", "convertir" -> convert(sender, args);
            default -> plugin.lang().send(sender, "errors.unknown-subcommand", Lang.unparsed("label", LABEL));
        }
    }

    // ------------------------------------------------------------------ toggle / help

    private void toggle(CommandSender sender) {
        Player player = CommandUtil.requirePlayer(plugin, sender);
        if (player == null || !CommandUtil.checkPermission(plugin, sender, PERM_FLY)) {
            return;
        }
        // Anti-spam : chaque bascule déclenche événements, règles, actions configurées et téléportation.
        long cooldownMillis = plugin.settings().commands().toggleCooldownMillis();
        if (cooldownMillis > 0L) {
            UUID id = player.getUniqueId();
            long nowMillis = System.currentTimeMillis();
            Long next = toggleCooldowns.get(id);
            if (next != null && next > nowMillis) {
                plugin.lang().send(sender, "fly.toggle-cooldown");
                return;
            }
            if (toggleCooldowns.size() > 256) {
                toggleCooldowns.values().removeIf(t -> t <= nowMillis);
            }
            toggleCooldowns.put(id, nowMillis + cooldownMillis);
        }
        FlightService flight = plugin.flightService();
        boolean enabling = !flight.isEnabled(player);
        ToggleResult result = flight.toggle(player, DisableCause.PLAYER);
        if (!result.changed()) {
            CommandUtil.sendSelfToggleFailure(plugin, player, result, enabling);
        }
    }

    private void help(CommandSender sender) {
        if (CommandUtil.requirePlayer(plugin, sender) == null
                || !CommandUtil.checkPermission(plugin, sender, PERM_FLY)) {
            return;
        }
        plugin.lang().send(sender, "help.player");
    }

    // ------------------------------------------------------------------ give

    private void give(CommandSender sender, String[] args) {
        Player giver = CommandUtil.requirePlayer(plugin, sender);
        if (giver == null || !CommandUtil.checkPermission(plugin, sender, PERM_GIVE)) {
            return;
        }
        Lang lang = plugin.lang();
        Settings settings = plugin.settings();
        Settings.GiveSection cfg = settings.give();
        if (!cfg.enabled()) {
            lang.send(sender, "errors.feature-disabled");
            return;
        }
        if (plugin.timedFly().enabled()) {
            lang.send(sender, "errors.timed-fly-locked");
            return;
        }
        if (args.length != 3) {
            lang.send(sender, "errors.usage", Lang.unparsed("usage", "/fly give <joueur> <temps>"));
            return;
        }
        String targetName = args[1];
        if (!CommandUtil.isValidName(targetName)) {
            lang.send(sender, "errors.invalid-player-name");
            return;
        }
        Player target = CommandUtil.onlineVisible(sender, targetName);
        if (target == null) {
            lang.send(sender, "give.target-offline", CommandUtil.player(targetName));
            return;
        }
        if (target.getUniqueId().equals(giver.getUniqueId())) {
            lang.send(sender, "give.self");
            return;
        }
        long min = Math.max(1L, cfg.minSeconds());
        long max = Math.min(cfg.maxSeconds(), settings.limits().maxSeconds());
        OptionalLong parsed = CommandUtil.parseTime(plugin, sender, args[2], min, max);
        if (parsed.isEmpty()) {
            return;
        }
        long amount = parsed.getAsLong();
        FlightService flight = plugin.flightService();
        if (flight.hasUnlimited(giver)) {
            lang.send(sender, "give.unlimited");
            return;
        }
        UUID giverId = giver.getUniqueId();
        long nowMillis = System.currentTimeMillis();
        Long next = giveCooldowns.get(giverId);
        if (next != null && next > nowMillis) {
            long remaining = (next - nowMillis + 999L) / 1000L;
            lang.send(sender, "give.cooldown", CommandUtil.time(plugin, remaining));
            return;
        }
        if (next != null) {
            giveCooldowns.remove(giverId);
        }
        if (giveCooldowns.size() > 256) {
            giveCooldowns.values().removeIf(t -> t <= nowMillis);
        }
        if (pending.contains(giverId)) {
            lang.send(sender, "errors.profile-loading");
            return;
        }
        FlightProfile giverProfile = flight.profile(giverId);
        if (giverProfile == null || flight.profile(target.getUniqueId()) == null) {
            lang.send(sender, "errors.profile-loading");
            return;
        }
        if (giverProfile.remainingSeconds() < amount) {
            lang.send(sender, "give.insufficient");
            return;
        }

        pending.add(giverId);
        UUID targetId = target.getUniqueId();
        String targetDisplay = target.getName();
        String giverName = giver.getName();
        if (cfg.cooldownSeconds() > 0L) {
            giveCooldowns.put(giverId, nowMillis + cfg.cooldownSeconds() * 1000L);
        }
        // 1) Débit du donneur. 2) Crédit de la cible du montant réellement débité.
        // 3) Remboursement de la part non créditée (cible au plafond, annulation, erreur).
        CompletableFuture<TimeChangeResult> debitCall;
        try {
            debitCall = flight.removeTime(giverId, amount, giver);
        } catch (RuntimeException e) {
            debitCall = CompletableFuture.failedFuture(e);
        }
        CompletableFuture<TimeChangeResult> debitFuture = debitCall
                .whenComplete((r, e) -> {
                    if (e != null) {
                        runMain(() -> {
                            pending.remove(giverId);
                            giveCooldowns.remove(giverId);
                        });
                    }
                });
        CommandUtil.onMain(plugin, sender, debitFuture, debit -> {
            if (!debit.success()) {
                pending.remove(giverId);
                giveCooldowns.remove(giverId);
                CommandUtil.sendTimeChangeFailure(plugin, sender, debit, giverName);
                return;
            }
            long debited = Math.max(0L, debit.oldSeconds() - debit.newSeconds());
            if (debited <= 0L) {
                pending.remove(giverId);
                giveCooldowns.remove(giverId);
                lang.send(sender, "give.insufficient");
                return;
            }
            transferCredit(sender, giverId, giverName, targetId, targetDisplay, debited);
        });
    }

    private void transferCredit(CommandSender sender, UUID giverId, String giverName, UUID targetId,
                                String targetName, long debited) {
        Lang lang = plugin.lang();
        FlightService flight = plugin.flightService();
        Player giverOnline = Bukkit.getPlayer(giverId);
        CommandSender actor = giverOnline != null ? giverOnline : sender;
        CompletableFuture<TimeChangeResult> creditFuture;
        try {
            creditFuture = flight.addTime(targetId, debited, actor);
        } catch (RuntimeException e) {
            creditFuture = CompletableFuture.failedFuture(e);
        }
        creditFuture.whenComplete((credit, error) -> {
            // addTime se termine sur le main thread (contrat FlightService) ; garde-fou si ce n'est pas le cas.
            Runnable task = () -> {
                long credited = 0L;
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "/fly give : crédit en échec", error);
                } else if (credit.success()) {
                    credited = Math.max(0L, Math.min(debited, credit.newSeconds() - credit.oldSeconds()));
                }
                long refund = debited - credited;
                if (refund > 0L) {
                    refund(giverId, refund, actor);
                }
                pending.remove(giverId);
                if (credited <= 0L) {
                    giveCooldowns.remove(giverId);
                    if (error != null || credit == null) {
                        lang.send(sender, "errors.storage");
                    } else {
                        CommandUtil.sendTimeChangeFailure(plugin, sender, credit, targetName);
                    }
                    return;
                }
                persistNow(giverId, targetId);
                lang.send(sender, "give.sent", CommandUtil.time(plugin, credited), CommandUtil.player(targetName));
                Player targetOnline = Bukkit.getPlayer(targetId);
                if (targetOnline != null) {
                    lang.send(targetOnline, "give.received", CommandUtil.time(plugin, credited),
                            CommandUtil.player(giverName));
                }
            };
            runMain(task);
        });
    }

    /**
     * Écrit tout de suite (une transaction, thread I/O) le débit du donneur et le crédit de la cible, sans
     * attendre le flush périodique : réduit la fenêtre où un changement de serveur (MySQL) ou un crash
     * relirait un solde non débité. Les profils restent marqués modifiés (le flush réécrira l'état courant).
     */
    private void persistNow(UUID giverId, UUID targetId) {
        FlightService flight = plugin.flightService();
        List<FlightProfileSnapshot> snapshots = new ArrayList<>(2);
        for (UUID id : List.of(giverId, targetId)) {
            FlightProfile p = flight.profile(id);
            if (p != null) {
                snapshots.add(p.snapshot());
            }
        }
        if (snapshots.isEmpty()) {
            return;
        }
        try {
            plugin.storage().repository().saveAll(snapshots).whenComplete((ignored, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING,
                            "/fly give : sauvegarde immédiate impossible (le flush périodique réessaiera)", error);
                }
            });
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "/fly give : sauvegarde immédiate impossible", e);
        }
    }

    private void refund(UUID giverId, long amount, CommandSender actor) {
        CompletableFuture<TimeChangeResult> future;
        try {
            future = plugin.flightService().addTime(giverId, amount, actor);
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((r, e) -> {
            if (e != null || r == null || !r.success()) {
                // Journalisé pour réconciliation manuelle : jamais de perte silencieuse.
                plugin.getLogger().warning("/fly give : remboursement de " + amount + "s impossible pour " + giverId
                        + (r != null ? " (" + r.status() + ")" : ""));
            }
        });
    }

    // ------------------------------------------------------------------ convert

    private void convert(CommandSender sender, String[] args) {
        Player player = CommandUtil.requirePlayer(plugin, sender);
        if (player == null || !CommandUtil.checkPermission(plugin, sender, PERM_TOKEN)) {
            return;
        }
        Lang lang = plugin.lang();
        Settings settings = plugin.settings();
        if (!settings.token().enabled()) {
            lang.send(sender, "errors.feature-disabled");
            return;
        }
        if (args.length != 2) {
            lang.send(sender, "errors.usage", Lang.unparsed("usage", "/fly convert <temps>"));
            return;
        }
        Settings.TokenSection cfg = settings.token();
        long min = Math.max(1L, cfg.minSeconds());
        long max = Math.min(cfg.maxSeconds(), settings.limits().maxSeconds());
        OptionalLong parsed = CommandUtil.parseTime(plugin, sender, args[1], min, max);
        if (parsed.isEmpty()) {
            return;
        }
        UUID id = player.getUniqueId();
        long nowMillis = System.currentTimeMillis();
        Long next = convertCooldowns.get(id);
        if (next != null && next > nowMillis) {
            long remaining = (next - nowMillis + 999L) / 1000L;
            lang.send(sender, "token.cooldown", CommandUtil.time(plugin, remaining));
            return;
        }
        if (next != null) {
            convertCooldowns.remove(id);
        }
        if (convertCooldowns.size() > 256) {
            convertCooldowns.values().removeIf(t -> t <= nowMillis);
        }
        if (!pending.add(id)) {
            lang.send(sender, "errors.profile-loading");
            return;
        }
        long seconds = parsed.getAsLong();
        long cooldownMillis = cfg.cooldownSeconds() * 1000L;
        CommandUtil.onMain(plugin, sender, CommandUtil.call(() -> plugin.tokenService().createForPlayer(player, seconds))
                        .whenComplete((r, e) -> runMain(() -> {
                            pending.remove(id);
                            // Échéance posée seulement après une création réussie ; effacée sur échec.
                            if (e == null && (r == TokenResult.SUCCESS || r == TokenResult.SUCCESS_DROPPED)
                                    && cooldownMillis > 0L) {
                                convertCooldowns.put(id, System.currentTimeMillis() + cooldownMillis);
                            } else if (e != null || (r != TokenResult.SUCCESS && r != TokenResult.SUCCESS_DROPPED)) {
                                convertCooldowns.remove(id);
                            }
                        })),
                result -> sendTokenResult(sender, result, seconds, min, max));
    }

    /** Exécute sur le main thread (immédiatement si on y est déjà). */
    private void runMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private void sendTokenResult(CommandSender sender, TokenResult result, long seconds, long min, long max) {
        Lang lang = plugin.lang();
        switch (result) {
            case SUCCESS -> lang.send(sender, "token.created", CommandUtil.time(plugin, seconds));
            case SUCCESS_DROPPED -> {
                lang.send(sender, "token.created", CommandUtil.time(plugin, seconds));
                lang.send(sender, "token.inventory-full");
            }
            case DISABLED -> lang.send(sender, "errors.feature-disabled");
            case INSUFFICIENT_TIME -> lang.send(sender, "token.insufficient");
            case INVALID_AMOUNT -> CommandUtil.sendBounds(plugin, sender, min, max);
            case UNLIMITED -> lang.send(sender, "give.unlimited");
            case TIMED_FLY_LOCKED -> lang.send(sender, "errors.timed-fly-locked");
            case STORAGE_ERROR -> lang.send(sender, "token.error");
        }
    }

    // ------------------------------------------------------------------ auto-complétion

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player)) {
            return List.of();
        }
        String[] a = CommandUtil.completionArgs(args);
        if (a.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission(PERM_FLY)) {
                subs.add("toggle");
                subs.add("help");
            }
            if (sender.hasPermission(FlyTimeCommand.PERM_TIME)) {
                subs.add("time");
            }
            if (sender.hasPermission(PERM_GIVE)) {
                subs.add("give");
            }
            if (sender.hasPermission(PERM_TOKEN)) {
                subs.add("convert");
            }
            return CommandUtil.filter(subs, a[0]);
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) {
            return switch (sub) {
                case "time", "temps" -> sender.hasPermission(FlyTimeCommand.PERM_TIME)
                        ? timeCommand.suggestPlayer(sender, a[1]) : List.of();
                case "give", "donner" -> sender.hasPermission(PERM_GIVE)
                        ? CommandUtil.playerNames(sender, a[1], true) : List.of();
                case "convert", "convertir" -> sender.hasPermission(PERM_TOKEN)
                        ? CommandUtil.times(a[1]) : List.of();
                default -> List.of();
            };
        }
        if (a.length == 3 && (sub.equals("give") || sub.equals("donner")) && sender.hasPermission(PERM_GIVE)) {
            return CommandUtil.times(a[2]);
        }
        return List.of();
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(PERM_FLY) || sender.hasPermission(FlyTimeCommand.PERM_TIME)
                || sender.hasPermission(PERM_GIVE) || sender.hasPermission(PERM_TOKEN);
    }

    /**
     * Oublie l'état d'un joueur qui se déconnecte (cooldowns {@code give}/{@code convert} conservés : anti-contournement).
     *
     * @param uuid joueur
     */
    public void forget(UUID uuid) {
        pending.remove(uuid);
        toggleCooldowns.remove(uuid);
        long now = System.currentTimeMillis();
        Long next = giveCooldowns.get(uuid);
        if (next != null && next <= now) {
            giveCooldowns.remove(uuid);
        }
        Long nextConvert = convertCooldowns.get(uuid);
        if (nextConvert != null && nextConvert <= now) {
            convertCooldowns.remove(uuid);
        }
    }
}
