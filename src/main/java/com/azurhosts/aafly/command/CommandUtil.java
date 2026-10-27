package com.azurhosts.aafly.command;

import com.azurhosts.aafly.AAFlyPlugin;
import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.TimeChangeResult;
import com.azurhosts.aafly.core.ToggleResult;
import com.azurhosts.aafly.time.DurationParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Outils partagés par les commandes AAFly : analyse des arguments (durées, pseudos, flag {@code -s}),
 * résolution des joueurs en ligne / hors-ligne, retour sur le main thread et auto-complétion.
 *
 * <p><b>Sécurité :</b> les saisies ne sont jamais insérées dans du MiniMessage (toujours via
 * {@link Lang#unparsed(String, String)}), les pseudos sont validés par {@link FlightProfile#NAME_PATTERN}
 * avant toute recherche, et les durées sont bornées. Main thread uniquement, sauf mention contraire.
 *
 * <p>AAFly par AzurHOSTS.
 */
public final class CommandUtil {

    /** Flag « silencieux pour la cible ». */
    public static final String FLAG_SILENT = "-s";
    /** Nombre maximal de suggestions renvoyées. */
    public static final int MAX_SUGGESTIONS = 64;
    /** Longueur maximale d'une saisie renvoyée dans un message d'erreur. */
    private static final int MAX_ECHO_LENGTH = DurationParser.MAX_INPUT_LENGTH;

    private CommandUtil() {
    }

    // ------------------------------------------------------------------ arguments

    /**
     * Arguments débarrassés du flag {@code -s}.
     *
     * @param args   arguments restants (sans le flag)
     * @param silent {@code true} si {@code -s} était présent
     */
    public record Parsed(List<String> args, boolean silent) {
        public Parsed {
            args = List.copyOf(args);
        }

        /**
         * @param index index
         * @return argument ou {@code null}
         */
        public @Nullable String arg(int index) {
            return index >= 0 && index < args.size() ? args.get(index) : null;
        }

        /**
         * @return nombre d'arguments
         */
        public int size() {
            return args.size();
        }
    }

    /**
     * Retire le flag {@code -s} (insensible à la casse, à n'importe quelle position).
     *
     * @param args arguments bruts
     * @param from index de départ (les arguments précédents sont ignorés, ex. la sous-commande)
     * @return arguments analysés
     */
    public static Parsed parseFlags(String[] args, int from) {
        List<String> out = new ArrayList<>(Math.max(0, args.length - from));
        boolean silent = false;
        for (int i = Math.max(0, from); i < args.length; i++) {
            String a = args[i];
            if (a == null || a.isEmpty()) {
                continue;
            }
            if (FLAG_SILENT.equalsIgnoreCase(a)) {
                silent = true;
            } else {
                out.add(a);
            }
        }
        return new Parsed(out, silent);
    }

    /**
     * Normalise le tableau d'arguments fourni par Paper (jamais {@code null}).
     *
     * @param args arguments
     * @return arguments non nuls
     */
    public static String[] safeArgs(String @Nullable [] args) {
        return args == null ? new String[0] : args;
    }

    /**
     * @param name pseudo saisi
     * @return {@code true} si le pseudo respecte {@code ^[A-Za-z0-9_]{1,16}$}
     */
    public static boolean isValidName(@Nullable String name) {
        return name != null && FlightProfile.NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Analyse une durée bornée ({@code 90}, {@code 1h30m}, {@code 2d}...). En cas d'échec, envoie le
     * message d'erreur adapté (format invalide ou hors bornes).
     *
     * @param plugin plugin
     * @param sender destinataire des erreurs
     * @param input  saisie
     * @param min    minimum inclus (≥ 1)
     * @param max    maximum inclus
     * @return durée en secondes, ou vide (message déjà envoyé)
     */
    public static OptionalLong parseTime(AAFlyPlugin plugin, CommandSender sender, @Nullable String input,
                                         long min, long max) {
        long lo = Math.max(1L, min);
        long hi = Math.max(lo, max);
        OptionalLong parsed = DurationParser.parse(input == null ? "" : input);
        if (parsed.isEmpty()) {
            plugin.lang().send(sender, "errors.invalid-time", Lang.unparsed("input", echo(input)));
            return OptionalLong.empty();
        }
        long v = parsed.getAsLong();
        if (v < lo || v > hi) {
            sendBounds(plugin, sender, lo, hi);
            return OptionalLong.empty();
        }
        return parsed;
    }

    /**
     * Envoie {@code errors.time-out-of-bounds}.
     *
     * @param plugin plugin
     * @param sender destinataire
     * @param min    minimum
     * @param max    maximum
     */
    public static void sendBounds(AAFlyPlugin plugin, CommandSender sender, long min, long max) {
        Lang lang = plugin.lang();
        lang.send(sender, "errors.time-out-of-bounds",
                Lang.component("min", lang.parse(plugin.timeFormatter().format(min))),
                Lang.component("max", lang.parse(plugin.timeFormatter().format(max))));
    }

    /**
     * Tronque une saisie avant de la renvoyer dans un message (insérée en texte littéral).
     *
     * @param input saisie
     * @return texte sûr à afficher
     */
    public static String echo(@Nullable String input) {
        if (input == null) {
            return "";
        }
        String s = input.replaceAll("[\\p{Cntrl}]", "");
        return s.length() > MAX_ECHO_LENGTH ? s.substring(0, MAX_ECHO_LENGTH) + "…" : s;
    }

    /**
     * Tag {@code <time>} formaté.
     *
     * @param plugin  plugin
     * @param seconds durée
     * @return résolveur
     */
    public static TagResolver time(AAFlyPlugin plugin, long seconds) {
        return plugin.lang().time(plugin.timeFormatter().format(seconds));
    }

    /**
     * Tag {@code <time>} « illimité ».
     *
     * @param plugin plugin
     * @return résolveur
     */
    public static TagResolver unlimitedTime(AAFlyPlugin plugin) {
        return plugin.lang().time(plugin.timeFormatter().unlimited());
    }

    /**
     * Tag {@code <player>} (texte littéral).
     *
     * @param name pseudo
     * @return résolveur
     */
    public static TagResolver player(String name) {
        return Lang.unparsed("player", name);
    }

    // ------------------------------------------------------------------ contrôles

    /**
     * Vérifie une permission et envoie {@code errors.no-permission} si elle manque.
     *
     * @param plugin     plugin
     * @param sender     émetteur
     * @param permission permission
     * @return {@code true} si autorisé
     */
    public static boolean checkPermission(AAFlyPlugin plugin, CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.lang().send(sender, "errors.no-permission");
        return false;
    }

    /**
     * @param plugin plugin
     * @param sender émetteur
     * @return le joueur, ou {@code null} (message {@code errors.player-only} envoyé)
     */
    public static @Nullable Player requirePlayer(AAFlyPlugin plugin, CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        plugin.lang().send(sender, "errors.player-only");
        return null;
    }

    /**
     * Joueur en ligne par pseudo exact, visible par l'émetteur (un joueur invisible n'est pas révélé).
     *
     * @param viewer émetteur
     * @param name   pseudo validé
     * @return joueur ou {@code null}
     */
    public static @Nullable Player onlineVisible(CommandSender viewer, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            return null;
        }
        if (viewer instanceof Player p && !p.canSee(target)) {
            return null;
        }
        return target;
    }

    // ------------------------------------------------------------------ résolution de joueurs

    /**
     * Cible résolue d'une commande.
     *
     * @param uuid    identifiant
     * @param name    pseudo connu
     * @param online  joueur en ligne (et visible), ou {@code null}
     * @param profile profil (en cache si en ligne), ou {@code null} s'il est en cours de chargement
     */
    public record Target(UUID uuid, String name, @Nullable Player online, @Nullable FlightProfile profile) {
    }

    /**
     * Résout un joueur en ligne (visible) ou hors-ligne déjà connu ({@code StorageManager#findByName}).
     * Le pseudo doit avoir été validé par {@link #isValidName(String)}.
     *
     * @param plugin plugin
     * @param viewer émetteur
     * @param name   pseudo validé
     * @return cible ou vide si inconnue ; complétée sur le main thread
     */
    public static CompletableFuture<Optional<Target>> resolve(AAFlyPlugin plugin, CommandSender viewer, String name) {
        return resolve(plugin, viewer, name, true);
    }

    /**
     * Variante de {@link #resolve(AAFlyPlugin, CommandSender, String)} ; les commandes d'administration
     * passent {@code respectVisibility = false} pour agir aussi sur les joueurs invisibles (vanish).
     *
     * @param plugin            plugin
     * @param viewer            émetteur
     * @param name              pseudo validé
     * @param respectVisibility {@code true} pour ne pas révéler un joueur invisible pour l'émetteur
     * @return cible ou vide si inconnue ; complétée sur le main thread
     */
    public static CompletableFuture<Optional<Target>> resolve(AAFlyPlugin plugin, CommandSender viewer, String name,
                                                              boolean respectVisibility) {
        CommandSender effectiveViewer = respectVisibility ? viewer : Bukkit.getConsoleSender();
        Player online = onlineVisible(effectiveViewer, name);
        if (online != null) {
            FlightProfile profile = plugin.flightService().profile(online.getUniqueId());
            return CompletableFuture.completedFuture(
                    Optional.of(new Target(online.getUniqueId(), online.getName(), online, profile)));
        }
        return call(() -> plugin.storage().findByName(name)).thenApply(opt -> opt.map(p -> {
            // Le joueur peut s'être connecté entre-temps : on privilégie alors le profil en cache.
            Player now = Bukkit.isPrimaryThread() ? Bukkit.getPlayer(p.uuid()) : null;
            boolean visible = now != null && (!(effectiveViewer instanceof Player v) || v.canSee(now));
            FlightProfile cached = visible ? plugin.flightService().profile(p.uuid()) : null;
            return new Target(p.uuid(), p.lastKnownName(), visible ? now : null, cached != null ? cached : p);
        }));
    }

    // ------------------------------------------------------------------ asynchrone

    /**
     * Exécute {@code callback} sur le main thread quand {@code future} se termine. Une erreur est
     * journalisée et signalée à {@code sender} ({@code errors.storage}). Sans effet si le plugin est désactivé.
     *
     * @param plugin   plugin
     * @param sender   émetteur (destinataire de l'erreur éventuelle)
     * @param future   opération
     * @param callback suite, sur le main thread
     * @param <T>      type du résultat
     */
    public static <T> void onMain(AAFlyPlugin plugin, CommandSender sender, CompletableFuture<T> future,
                                  Consumer<T> callback) {
        future.whenComplete((value, error) -> {
            Runnable task = () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "Commande AAFly : opération asynchrone en échec", error);
                    plugin.lang().send(sender, "errors.storage");
                    return;
                }
                try {
                    callback.accept(value);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "Commande AAFly : erreur lors du traitement du résultat", e);
                    plugin.lang().send(sender, "errors.storage");
                }
            };
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        });
    }

    /**
     * Appelle une opération asynchrone en convertissant une exception synchrone en future échouée
     * (les verrous posés par l'appelant sont ainsi toujours libérés par ses callbacks).
     *
     * @param call opération
     * @param <T>  type du résultat
     * @return future de l'opération, ou future échouée
     */
    public static <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> call) {
        try {
            CompletableFuture<T> f = call.get();
            return f != null ? f : CompletableFuture.failedFuture(new IllegalStateException("future nulle"));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ------------------------------------------------------------------ résultats

    /**
     * Envoie le message d'échec d'une modification de temps (statuts autres que OK/CLAMPED).
     *
     * @param plugin plugin
     * @param sender destinataire
     * @param result résultat en échec
     * @param name   pseudo de la cible
     */
    public static void sendTimeChangeFailure(AAFlyPlugin plugin, CommandSender sender, TimeChangeResult result,
                                             String name) {
        Lang lang = plugin.lang();
        switch (result.status()) {
            case UNKNOWN_PLAYER -> lang.send(sender, "admin.unknown-player", player(name));
            case CANCELLED -> lang.send(sender, "errors.cancelled");
            case TIMED_FLY_LOCKED -> lang.send(sender, "errors.timed-fly-locked");
            case INVALID_AMOUNT -> sendBounds(plugin, sender, 1L, plugin.settings().limits().maxSeconds());
            case STORAGE_ERROR -> lang.send(sender, "errors.storage");
            case OK, CLAMPED -> {
                // succès : rien à signaler ici
            }
        }
    }

    /**
     * Message adressé au joueur lui-même quand son activation/désactivation n'a rien changé.
     * Convention : {@code FlightService} envoie les messages d'état (ENABLED/DISABLED), l'appelant ceux des refus.
     *
     * @param plugin   plugin
     * @param player   joueur concerné
     * @param result   résultat
     * @param enabling {@code true} si l'action visait à activer
     */
    public static void sendSelfToggleFailure(AAFlyPlugin plugin, Player player, ToggleResult result,
                                             boolean enabling) {
        Lang lang = plugin.lang();
        switch (result) {
            case ALREADY -> lang.send(player, enabling ? "fly.already-enabled" : "fly.already-disabled");
            case NO_TIME -> lang.send(player, plugin.timedFly().enabled() && plugin.timedFly().groupOf(player).isEmpty()
                    ? "timed-fly.no-group" : "fly.no-time");
            case DENIED_BY_RULE -> {
                // FlightService envoie déjà fly.not-allowed-here et exécute les actions de la règle.
            }
            case DENIED_NEARBY -> lang.send(player, "fly.nearby-enemy");
            case TIMED_FLY_COOLDOWN -> {
                FlightProfile profile = plugin.flightService().profile(player.getUniqueId());
                long remaining = profile == null ? 0L
                        : plugin.timedFly().cooldownRemaining(profile, System.currentTimeMillis() / 1000L);
                lang.send(player, "timed-fly.cooldown", time(plugin, remaining));
            }
            case CANCELLED -> lang.send(player, "errors.cancelled");
            case NOT_LOADED -> lang.send(player, "errors.profile-loading");
            case ENABLED, DISABLED -> {
                // changement effectif : message envoyé par FlightService
            }
        }
    }

    /**
     * Raison lisible d'un refus d'activation (insérée en texte littéral dans {@code <reason>}).
     *
     * @param result résultat
     * @return texte
     */
    public static String reason(ToggleResult result) {
        return switch (result) {
            case ALREADY -> "déjà dans cet état";
            case NO_TIME -> "aucun temps de vol";
            case DENIED_BY_RULE -> "interdit par une règle";
            case DENIED_NEARBY -> "ennemi à proximité";
            case TIMED_FLY_COOLDOWN -> "recharge timed-fly en cours";
            case CANCELLED -> "annulé par un autre plugin";
            case NOT_LOADED -> "profil en cours de chargement";
            case ENABLED -> "activé";
            case DISABLED -> "désactivé";
        };
    }

    // ------------------------------------------------------------------ auto-complétion

    /**
     * Filtre les candidats par préfixe (insensible à la casse), limité à {@link #MAX_SUGGESTIONS}.
     *
     * @param candidates candidats
     * @param prefix     saisie partielle
     * @return suggestions
     */
    public static List<String> filter(Collection<String> candidates, @Nullable String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(c);
                if (out.size() >= MAX_SUGGESTIONS) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Pseudos des joueurs en ligne visibles par l'émetteur.
     *
     * @param viewer    émetteur
     * @param prefix    saisie partielle
     * @param excludeSelf exclure l'émetteur
     * @return suggestions
     */
    public static List<String> playerNames(CommandSender viewer, @Nullable String prefix, boolean excludeSelf) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (viewer instanceof Player v) {
                if (!v.canSee(online) || (excludeSelf && v.getUniqueId().equals(online.getUniqueId()))) {
                    continue;
                }
            }
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(online.getName());
                if (out.size() >= MAX_SUGGESTIONS) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Suggestions de durée ({@code 30s}, {@code 10m}... ou complétion de l'unité).
     *
     * @param prefix saisie partielle
     * @return suggestions
     */
    public static List<String> times(@Nullable String prefix) {
        return DurationParser.suggestions(prefix == null ? "" : prefix);
    }

    /**
     * Ajoute le flag {@code -s} aux suggestions si la saisie le permet.
     *
     * @param out    suggestions (modifiée)
     * @param prefix saisie partielle
     * @param args   arguments déjà saisis
     * @return {@code out}
     */
    public static List<String> withSilentFlag(List<String> out, @Nullable String prefix, String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (FLAG_SILENT.equalsIgnoreCase(args[i])) {
                return out;
            }
        }
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        if (FLAG_SILENT.startsWith(p)) {
            List<String> copy = new ArrayList<>(out);
            copy.add(FLAG_SILENT);
            return copy;
        }
        return out;
    }

    /**
     * Arguments pour l'auto-complétion : garantit au moins un argument (éventuellement vide).
     *
     * @param args arguments fournis par Paper
     * @return arguments non vides
     */
    public static String[] completionArgs(String @Nullable [] args) {
        if (args == null || args.length == 0) {
            return new String[]{""};
        }
        return args;
    }

    /**
     * @return horodatage courant en secondes
     */
    public static long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
