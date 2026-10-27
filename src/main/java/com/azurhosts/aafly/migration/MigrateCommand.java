package com.azurhosts.aafly.migration;

import com.azurhosts.aafly.AAFlyPlugin;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.storage.DefaultStorageManager;
import com.azurhosts.aafly.storage.StorageManager;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

/**
 * Sous-commande {@code /aafly migrate} (SPEC-phase2 A9), <b>console uniquement</b>, permission
 * {@value #PERMISSION} :
 * <pre>
 * /aafly migrate wfly [sqlite|mysql] [--merge=max|replace|skip] [--dry-run]
 * /aafly migrate tempfly [--merge=max|replace|skip] [--dry-run]
 * </pre>
 * Les sources sont des chemins fixes sous {@code plugins} (aucun chemin saisi). Les messages sont du texte
 * brut ({@link Component#text}) : aucune valeur n'est interprétée comme MiniMessage.
 */
public final class MigrateCommand {

    /** Permission de la migration. */
    public static final String PERMISSION = "aafly.admin.migrate";
    /** Usage affiché. */
    public static final String USAGE =
            "/aafly migrate <wfly [sqlite|mysql] | tempfly> [--merge=max|replace|skip] [--dry-run]";

    private static final String FLAG_DRY_RUN = "--dry-run";
    private static final String FLAG_MERGE = "--merge=";

    private final AAFlyPlugin plugin;
    private final MigrationService service;

    /**
     * @param plugin plugin
     */
    public MigrateCommand(AAFlyPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.service = new MigrationService(plugin);
    }

    /**
     * @param args arguments de {@code /aafly} ({@code args[0] = "migrate"})
     * @return demande, ou vide si la syntaxe est invalide
     */
    static Optional<MigrationService.Request> parse(String[] args) {
        if (args.length < 2) {
            return Optional.empty();
        }
        String source = args[1].toLowerCase(Locale.ROOT);
        MergeStrategy strategy = MergeStrategy.DEFAULT;
        boolean dryRun = false;
        boolean mergeSeen = false;
        String engine = null;
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            String lower = a.toLowerCase(Locale.ROOT);
            if (lower.equals(FLAG_DRY_RUN)) {
                dryRun = true;
            } else if (lower.startsWith(FLAG_MERGE) && !mergeSeen) {
                Optional<MergeStrategy> m = MergeStrategy.parse(lower.substring(FLAG_MERGE.length()));
                if (m.isEmpty()) {
                    return Optional.empty();
                }
                strategy = m.get();
                mergeSeen = true;
            } else if (source.equals("wfly") && engine == null && (lower.equals("sqlite") || lower.equals("mysql"))) {
                engine = lower;
            } else {
                return Optional.empty();
            }
        }
        MigrationService.Source src = switch (source) {
            case "wfly" -> "mysql".equals(engine) ? MigrationService.Source.WFLY_MYSQL
                    : MigrationService.Source.WFLY_SQLITE;
            case "tempfly" -> MigrationService.Source.TEMPFLY;
            default -> null;
        };
        return src == null ? Optional.empty() : Optional.of(new MigrationService.Request(src, strategy, dryRun));
    }

    /**
     * @param sender émetteur
     * @param args   arguments de {@code /aafly} ({@code args[0] = "migrate"})
     */
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(Component.text("Cette commande est réservée à la console du serveur.",
                    NamedTextColor.RED));
            return;
        }
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("Permission manquante : " + PERMISSION, NamedTextColor.RED));
            return;
        }
        Optional<MigrationService.Request> parsed = parse(args);
        if (parsed.isEmpty()) {
            sender.sendMessage(Component.text("Usage : " + USAGE, NamedTextColor.YELLOW));
            return;
        }
        StorageManager sm = plugin.storage();
        if (!(sm instanceof DefaultStorageManager storage)) {
            sender.sendMessage(Component.text("Stockage AAFly indisponible pour la migration.", NamedTextColor.RED));
            return;
        }
        Settings settings = plugin.settings();
        File dataFolder = plugin.getDataFolder();
        File pluginsFolder = dataFolder.getAbsoluteFile().getParentFile();
        if (pluginsFolder == null) {
            sender.sendMessage(Component.text("Dossier plugins introuvable.", NamedTextColor.RED));
            return;
        }
        Path pluginsDir = pluginsFolder.toPath();
        MigrationService.Request request = parsed.get();
        boolean started = service.start(request, storage, pluginsDir, settings.limits().maxSeconds(),
                settings.flight().defaultSpeed(), outcome -> report(sender, request, outcome));
        if (!started) {
            sender.sendMessage(Component.text("Une migration est déjà en cours.", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Migration " + request.source().label() + " lancée (fusion "
                + request.strategy().id() + (request.dryRun() ? ", essai à blanc" : "") + ")...",
                NamedTextColor.GRAY));
    }

    private static void report(CommandSender sender, MigrationService.Request request,
                               MigrationService.Outcome outcome) {
        if (outcome.error() != null || outcome.report() == null) {
            sender.sendMessage(Component.text("Migration " + request.source().label() + " échouée : "
                    + outcome.error() + " Aucune donnée n'a été écrite.", NamedTextColor.RED));
            return;
        }
        ImportReport r = outcome.report();
        sender.sendMessage(Component.text("Migration " + request.source().label()
                + (r.dryRun() ? " (essai à blanc, rien n'a été écrit)" : " terminée") + " :", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  lus : " + r.read() + " | importés : " + r.imported()
                + " (dont mis à jour : " + r.updated() + ") | ignorés : " + r.ignored()
                + " | déjà présents : " + r.alreadyPresent(), NamedTextColor.WHITE));
        if (!r.dryRun()) {
            sender.sendMessage(Component.text("  profils en ligne rechargés : " + outcome.reloadedOnline(),
                    NamedTextColor.GRAY));
        }
    }

    /**
     * Auto-complétion (console uniquement).
     *
     * @param sender émetteur
     * @param args   arguments de {@code /aafly} ({@code args[0] = "migrate"}, dernier = saisie en cours)
     * @return propositions
     */
    public List<String> suggest(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || !sender.hasPermission(PERMISSION) || args.length < 2) {
            return List.of();
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length == 2) {
            options.addAll(List.of("wfly", "tempfly"));
        } else {
            if (args.length == 3 && "wfly".equalsIgnoreCase(args[1])) {
                options.addAll(List.of("sqlite", "mysql"));
            }
            options.addAll(List.of("--merge=max", "--merge=replace", "--merge=skip", FLAG_DRY_RUN));
        }
        options.removeIf(o -> !o.startsWith(last));
        return options;
    }
}
