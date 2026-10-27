package com.azurhosts.aafly.core;

import com.azurhosts.aafly.action.Action;
import com.azurhosts.aafly.action.ActionExecutor;
import com.azurhosts.aafly.action.TimeThresholdNotifier;
import com.azurhosts.aafly.api.events.FlightExpireEvent;
import com.azurhosts.aafly.api.events.FlightTimeChangeEvent;
import com.azurhosts.aafly.api.events.FlightToggleEvent;
import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.config.MessagesConfig;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import com.azurhosts.aafly.rules.Rule;
import com.azurhosts.aafly.rules.RuleContext;
import com.azurhosts.aafly.rules.RuleDecision;
import com.azurhosts.aafly.rules.RuleEngine;
import com.azurhosts.aafly.storage.StorageManager;
import com.azurhosts.aafly.time.TimeFormatter;
import com.azurhosts.aafly.time.TimeMath;
import com.azurhosts.aafly.timedfly.TimedFlyService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Implémentation de {@link FlightService} (SPEC §4). Confinée au main thread : toutes les mutations de
 * {@link FlightProfile} et tous les appels à l'API Bukkit ont lieu sur le main thread ; les opérations
 * sur des profils hors-ligne attendent le chargement par le stockage (thread {@code AAFly-IO}) puis
 * reviennent sur le main thread avant toute modification.
 *
 * <p><b>Permissions spéciales :</b> un opérateur n'obtient {@code aafly.unlimited} (et ses alias) que si
 * {@code flight.unlimited-for-op} est vrai ou si la permission lui est <i>explicitement</i> accordée ;
 * même principe pour {@code aafly.bypass.rules}. Cela évite que la valeur par défaut « OP » des
 * permissions non déclarées ne rende tout opérateur illimité.
 *
 * <p><b>Messages :</b> ce service envoie les messages d'<i>état</i> (activation {@code fly.enabled*},
 * désactivation {@code fly.disabled}/{@code fly.world-change}/{@code fly.gamemode-change}, expiration
 * {@code fly.expired}, règle {@code fly.not-allowed-here}, ennemi {@code fly.nearby-enemy} lors d'une coupure,
 * réactivation {@code fly.reactivated}) et les actions associées, sauf {@code silent}. Les messages de
 * <i>résultat</i> d'une demande refusée ({@code ALREADY}, {@code NO_TIME}, {@code TIMED_FLY_COOLDOWN},
 * {@code DENIED_NEARBY}, {@code CANCELLED}) restent à la charge de l'appelant (commande). Exception :
 * {@code DENIED_BY_RULE} envoie {@code fly.not-allowed-here} et exécute les actions de la règle (sauf silent).
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class DefaultFlightService implements FlightService {

    /** Opérations sur le temps. */
    private enum Op { ADD, REMOVE, SET, RESET }

    /** Fenêtre (ms) pendant laquelle un refus répété par la même règle n'exécute pas à nouveau ses actions. */
    private static final long DENIED_ACTIONS_WINDOW_MILLIS = 3_000L;
    /** Taille au-delà de laquelle les entrées expirées de {@link #deniedNotices} sont purgées. */
    private static final int DENIED_NOTICES_PRUNE_SIZE = 256;

    /** Dernier refus notifié (règle, instant) par joueur. */
    private record DeniedNotice(String rule, long atMillis) {
    }

    /** Anti-spam des actions de refus (main thread uniquement). */
    private final Map<UUID, DeniedNotice> deniedNotices = new HashMap<>();

    private final Logger logger;
    private final Supplier<Settings> settings;
    private final Lang lang;
    private final Supplier<TimeFormatter> formatter;
    private final StorageManager storage;
    private final RuleEngine rules;
    private final PlaceholderBridge placeholders;
    private final ActionExecutor actions;
    private final TimeThresholdNotifier thresholds;
    private final TimedFlyService timedFly;
    private final SafeLanding landing;
    private final FlySpeedService speed;
    private final NearbyEnemyDetector nearby;

    /**
     * @param logger       logger du plugin
     * @param settings     réglages courants (relus à chaque usage : compatible reload)
     * @param lang         rendu des messages
     * @param formatter    formateur de durées courant
     * @param storage      stockage des profils
     * @param rules        moteur de règles
     * @param placeholders pont PlaceholderAPI
     * @param actions      exécuteur d'actions
     * @param thresholds   notificateur de seuils
     * @param timedFly     service timed-fly
     * @param landing      atterrissage sécurisé
     * @param speed        vitesse de vol
     * @param nearby       détection d'ennemis proches
     */
    public DefaultFlightService(Logger logger, Supplier<Settings> settings, Lang lang, Supplier<TimeFormatter> formatter,
                                StorageManager storage, RuleEngine rules, PlaceholderBridge placeholders,
                                ActionExecutor actions, TimeThresholdNotifier thresholds, TimedFlyService timedFly,
                                SafeLanding landing, FlySpeedService speed, NearbyEnemyDetector nearby) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.placeholders = Objects.requireNonNull(placeholders, "placeholders");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
        this.timedFly = Objects.requireNonNull(timedFly, "timedFly");
        this.landing = Objects.requireNonNull(landing, "landing");
        this.speed = Objects.requireNonNull(speed, "speed");
        this.nearby = Objects.requireNonNull(nearby, "nearby");
    }

    // ------------------------------------------------------------------ activation / désactivation

    @Override
    public ToggleResult enable(Player player, DisableCause cause, boolean silent) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(cause, "cause");
        FlightProfile profile = storage.cached(player.getUniqueId());
        if (profile == null) {
            return ToggleResult.NOT_LOADED;
        }
        return doEnable(player, profile, cause, silent, false);
    }

    /**
     * @param force {@code true} pour ignorer le court-circuit « déjà activé » (restauration à la connexion :
     *              le drapeau vanilla {@code mayfly} sauvegardé ne doit jamais dispenser des vérifications)
     */
    private ToggleResult doEnable(Player player, FlightProfile profile, DisableCause cause, boolean silent,
                                  boolean force) {
        boolean wasSuspended = profile.isSuspended();
        if (!force && profile.flightEnabled() && !wasSuspended && player.getAllowFlight()) {
            return ToggleResult.ALREADY;
        }
        long now = nowSeconds();
        boolean unlimited = hasUnlimited(player);
        if (!unlimited) {
            if (timedFly.enabled()) {
                timedFly.refillIfReady(player, profile, now);
                if (profile.remainingSeconds() <= 0L) {
                    return timedFly.groupOf(player).isEmpty() ? ToggleResult.NO_TIME : ToggleResult.TIMED_FLY_COOLDOWN;
                }
            } else if (profile.remainingSeconds() <= 0L) {
                return ToggleResult.NO_TIME;
            }
        }
        if (!bypassesRules(player)) {
            RuleDecision decision = rules.evaluateFly(context(player, profile), false);
            if (!decision.allowed()) {
                if (!silent) {
                    notifyDenied(player, profile, decision);
                }
                return ToggleResult.DENIED_BY_RULE;
            }
        }
        // Détection PVP (SPEC §4) distincte des règles : aafly.bypass.rules n'en exempte pas.
        if (nearby.hasEnemyNearby(player)) {
            return ToggleResult.DENIED_NEARBY;
        }
        FlightToggleEvent event = new FlightToggleEvent(player, true, cause);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !player.isOnline()) {
            return ToggleResult.CANCELLED;
        }
        profile.setFlightEnabled(true);
        profile.setSuspendedBy(null);
        profile.setLastMoveEpochMillis(System.currentTimeMillis());
        profile.setLastUpdateEpochSeconds(now);
        storage.markDirty(profile);
        startFlight(player, profile);
        if (!silent) {
            if (wasSuspended) {
                lang.send(player, "fly.reactivated");
            } else {
                if (unlimited) {
                    lang.send(player, "fly.enabled-unlimited");
                } else {
                    lang.send(player, "fly.enabled", lang.time(formatter.get().format(profile.remainingSeconds())));
                }
                actions.runNamed(player, MessagesConfig.KEY_ON_ENABLE, vars(player, profile));
            }
        }
        return ToggleResult.ENABLED;
    }

    @Override
    public ToggleResult disable(Player player, DisableCause cause, boolean silent) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(cause, "cause");
        FlightProfile profile = storage.cached(player.getUniqueId());
        if (profile == null) {
            return ToggleResult.NOT_LOADED;
        }
        // SPEC §4 : un ennemi proche suspend toujours le vol (réactivé quand le joueur est libre) ;
        // une règle ne suspend que si flight.auto-reactivate-after-rule est actif.
        boolean suspension = cause == DisableCause.NEARBY
                || (cause == DisableCause.RULE && settings.get().flight().autoReactivateAfterRule());
        if (suspension ? (!profile.flightEnabled() || profile.isSuspended())
                : (!profile.flightEnabled() && !profile.isSuspended())) {
            return ToggleResult.ALREADY;
        }
        FlightToggleEvent event = new FlightToggleEvent(player, false, cause);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return ToggleResult.CANCELLED;
        }
        long now = nowSeconds();
        boolean wasActive = profile.flightEnabled() && !profile.isSuspended();
        if (suspension) {
            profile.setSuspendedBy(cause);
        } else {
            profile.setFlightEnabled(false);
            profile.setSuspendedBy(null);
        }
        profile.setLastUpdateEpochSeconds(now);
        storage.markDirty(profile);
        if (wasActive && player.isOnline()) {
            stopFlight(player);
        }
        if (!suspension && timedFly.enabled() && !hasUnlimited(player)) {
            timedFly.onFlightStopped(player, profile, now);
        }
        if (!silent) {
            Map<String, String> vars = vars(player, profile);
            switch (cause) {
                case EXPIRED -> {
                    lang.send(player, "fly.expired");
                    actions.runNamed(player, MessagesConfig.KEY_ON_EXPIRE, vars);
                }
                case RULE -> {
                    lang.send(player, "fly.not-allowed-here");
                    actions.runNamed(player, MessagesConfig.KEY_NOT_ALLOWED_HERE, vars);
                }
                case NEARBY -> lang.send(player, "fly.nearby-enemy");
                case WORLD_CHANGE -> {
                    lang.send(player, "fly.world-change");
                    actions.runNamed(player, MessagesConfig.KEY_ON_DISABLE, vars);
                }
                case GAMEMODE -> {
                    lang.send(player, "fly.gamemode-change");
                    actions.runNamed(player, MessagesConfig.KEY_ON_DISABLE, vars);
                }
                default -> {
                    lang.send(player, "fly.disabled");
                    actions.runNamed(player, MessagesConfig.KEY_ON_DISABLE, vars);
                }
            }
        }
        return ToggleResult.DISABLED;
    }

    /**
     * Le vol bascule selon l'état <i>voulu</i> : un vol suspendu (règle, ennemi proche) est considéré
     * comme voulu, {@code /fly} l'annule donc définitivement (pas de réactivation automatique).
     */
    @Override
    public ToggleResult toggle(Player player, DisableCause cause) {
        FlightProfile profile = storage.cached(player.getUniqueId());
        if (profile == null) {
            return ToggleResult.NOT_LOADED;
        }
        return profile.flightEnabled() ? disable(player, cause, false) : enable(player, cause, false);
    }

    @Override
    public boolean isEnabled(Player player) {
        FlightProfile profile = storage.cached(player.getUniqueId());
        return profile != null && profile.flightEnabled() && !profile.isSuspended();
    }

    @Override
    public RuleDecision evaluate(Player player) {
        FlightProfile profile = storage.cached(player.getUniqueId());
        if (profile == null || bypassesRules(player)) {
            return RuleDecision.DEFAULT_ALLOW;
        }
        return rules.evaluateFly(context(player, profile), false);
    }

    /**
     * Expiration (temps à 0) : émet {@link FlightExpireEvent} puis désactive (cause {@code EXPIRED}),
     * sauf si un listener a rajouté du temps entre-temps.
     *
     * @param player joueur en ligne
     */
    public void expire(Player player) {
        FlightProfile profile = storage.cached(player.getUniqueId());
        if (profile == null || !profile.flightEnabled()) {
            return;
        }
        Bukkit.getPluginManager().callEvent(new FlightExpireEvent(player));
        if (profile.remainingSeconds() > 0L || hasUnlimited(player)) {
            return;
        }
        if (profile.isSuspended()) {
            // Vol déjà coupé physiquement : on oublie le souhait de vol (et la recharge timed-fly démarre).
            abandonWish(player, profile);
            return;
        }
        disable(player, DisableCause.EXPIRED, false);
    }

    /**
     * Abandonne le souhait de vol hors de {@link #disable} (vol suspendu expiré, réactivation impossible,
     * restauration refusée) : {@code flightEnabled=false}, plus de suspension, profil marqué modifié et,
     * en timed-fly, démarrage de la recharge comme pour une désactivation normale. Ne touche pas à l'état
     * physique du joueur (à couper séparément via {@link #stopFlight} si nécessaire).
     *
     * @param player  joueur en ligne
     * @param profile son profil
     */
    public void abandonWish(Player player, FlightProfile profile) {
        long now = nowSeconds();
        profile.setFlightEnabled(false);
        profile.setSuspendedBy(null);
        profile.setLastUpdateEpochSeconds(now);
        storage.markDirty(profile);
        if (timedFly.enabled() && !hasUnlimited(player)) {
            timedFly.onFlightStopped(player, profile, now);
        }
    }

    /**
     * Suspension par une règle {@code fly-rules} (DENY) : désactivation de cause {@code RULE} puis
     * exécution des actions de la règle correspondante.
     *
     * @param player   joueur en vol
     * @param decision décision DENY
     * @return résultat de la désactivation
     */
    public ToggleResult suspendByRule(Player player, RuleDecision decision) {
        ToggleResult result = disable(player, DisableCause.RULE, false);
        if (result == ToggleResult.DISABLED) {
            runRuleActions(player, decision);
        }
        return result;
    }

    // ------------------------------------------------------------------ temps

    @Override
    public CompletableFuture<TimeChangeResult> addTime(UUID uuid, long seconds, @Nullable CommandSender actor) {
        return change(uuid, Op.ADD, seconds, actor, defaultReason(Op.ADD, actor));
    }

    @Override
    public CompletableFuture<TimeChangeResult> removeTime(UUID uuid, long seconds, @Nullable CommandSender actor) {
        return change(uuid, Op.REMOVE, seconds, actor, defaultReason(Op.REMOVE, actor));
    }

    @Override
    public CompletableFuture<TimeChangeResult> setTime(UUID uuid, long seconds, @Nullable CommandSender actor) {
        return change(uuid, Op.SET, seconds, actor, defaultReason(Op.SET, actor));
    }

    @Override
    public CompletableFuture<TimeChangeResult> reset(UUID uuid, @Nullable CommandSender actor) {
        return change(uuid, Op.RESET, 0L, actor, defaultReason(Op.RESET, actor));
    }

    @Override
    public CompletableFuture<TimeChangeResult> addTime(UUID uuid, long seconds, @Nullable CommandSender actor,
                                                        FlightTimeChangeEvent.Reason reason) {
        return change(uuid, Op.ADD, seconds, actor, Objects.requireNonNull(reason, "reason"));
    }

    @Override
    public CompletableFuture<TimeChangeResult> removeTime(UUID uuid, long seconds, @Nullable CommandSender actor,
                                                           FlightTimeChangeEvent.Reason reason) {
        return change(uuid, Op.REMOVE, seconds, actor, Objects.requireNonNull(reason, "reason"));
    }

    @Override
    public CompletableFuture<TimeChangeResult> setTime(UUID uuid, long seconds, @Nullable CommandSender actor,
                                                        FlightTimeChangeEvent.Reason reason) {
        return change(uuid, Op.SET, seconds, actor, Objects.requireNonNull(reason, "reason"));
    }

    @Override
    public int addAll(long seconds, @Nullable CommandSender actor) {
        return changeAll(Op.ADD, seconds, actor);
    }

    @Override
    public int removeAll(long seconds, @Nullable CommandSender actor) {
        return changeAll(Op.REMOVE, seconds, actor);
    }

    private int changeAll(Op op, long seconds, @Nullable CommandSender actor) {
        if (!validAmount(op, seconds) || timedFly.enabled()) {
            return 0;
        }
        FlightTimeChangeEvent.Reason reason = defaultReason(op, actor);
        int count = 0;
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            FlightProfile profile = storage.cached(p.getUniqueId());
            if (profile != null && applyChange(profile, op, seconds, actor, reason).success()) {
                count++;
            }
        }
        return count;
    }

    private CompletableFuture<TimeChangeResult> change(UUID uuid, Op op, long amount, @Nullable CommandSender actor,
                                                      FlightTimeChangeEvent.Reason reason) {
        Objects.requireNonNull(uuid, "uuid");
        if (!validAmount(op, amount)) {
            return CompletableFuture.completedFuture(TimeChangeResult.failure(TimeChangeResult.Status.INVALID_AMOUNT, uuid));
        }
        if (timedFly.enabled() && reason != FlightTimeChangeEvent.Reason.TIMED_FLY) {
            return CompletableFuture.completedFuture(
                    TimeChangeResult.failure(TimeChangeResult.Status.TIMED_FLY_LOCKED, uuid));
        }
        FlightProfile online = storage.cached(uuid);
        if (online != null) {
            return CompletableFuture.completedFuture(applyChange(online, op, amount, actor, reason));
        }
        CompletableFuture<TimeChangeResult> out = new CompletableFuture<>();
        storage.loadExisting(uuid).whenComplete((found, error) -> runOnMain(() -> {
            try {
                if (error != null) {
                    logger.log(Level.WARNING, "Lecture du profil de vol " + uuid + " impossible", error);
                    out.complete(TimeChangeResult.failure(TimeChangeResult.Status.STORAGE_ERROR, uuid));
                    return;
                }
                if (found == null || found.isEmpty()) {
                    out.complete(TimeChangeResult.failure(TimeChangeResult.Status.UNKNOWN_PLAYER, uuid));
                    return;
                }
                // Le joueur a pu se connecter entre-temps : le profil en ligne fait foi.
                FlightProfile cached = storage.cached(uuid);
                boolean offline = cached == null;
                TimeChangeResult result = applyChange(offline ? found.get() : cached, op, amount, actor, reason);
                if (result.success() && offline) {
                    storage.save(uuid).exceptionally(t -> {
                        logger.log(Level.WARNING, "Sauvegarde du profil hors-ligne " + uuid + " en erreur", t);
                        return null;
                    });
                }
                out.complete(result);
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Modification du temps de vol de " + uuid + " en erreur", e);
                out.complete(TimeChangeResult.failure(TimeChangeResult.Status.STORAGE_ERROR, uuid));
            }
        }));
        return out;
    }

    private boolean validAmount(Op op, long amount) {
        long max = maxSeconds();
        return switch (op) {
            case ADD, REMOVE -> amount > 0L && amount <= max;
            case SET -> amount >= 0L && amount <= max;
            case RESET -> true;
        };
    }

    private TimeChangeResult applyChange(FlightProfile profile, Op op, long amount, @Nullable CommandSender actor,
                                         FlightTimeChangeEvent.Reason reason) {
        UUID uuid = profile.uuid();
        long max = maxSeconds();
        long old = profile.remainingSeconds();
        long ideal = switch (op) {
            case ADD -> saturatedAdd(old, amount);
            case REMOVE -> old - amount;
            case SET -> amount;
            case RESET -> 0L;
        };
        long proposed = TimeMath.clamp(ideal, max);
        Player online = Bukkit.getPlayer(uuid);
        FlightTimeChangeEvent event = new FlightTimeChangeEvent(uuid, online, actor, reason, old, proposed);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return new TimeChangeResult(TimeChangeResult.Status.CANCELLED, uuid, old, old, profile);
        }
        long requested = event.getNewSeconds();
        long applied = TimeMath.clamp(requested, max);
        boolean clamped = requested != applied || (requested == proposed && ideal != proposed);
        profile.setRemainingSeconds(applied);
        storage.markDirty(profile);
        if (applied > old) {
            thresholds.reset(uuid);
        }
        if (online != null && applied == 0L && profile.flightEnabled() && !hasUnlimited(online)) {
            expire(online);
        }
        return new TimeChangeResult(clamped ? TimeChangeResult.Status.CLAMPED : TimeChangeResult.Status.OK,
                uuid, old, applied, profile);
    }

    private static FlightTimeChangeEvent.Reason defaultReason(Op op, @Nullable CommandSender actor) {
        if (actor == null) {
            return FlightTimeChangeEvent.Reason.API;
        }
        return switch (op) {
            case ADD -> FlightTimeChangeEvent.Reason.ADD;
            case REMOVE -> FlightTimeChangeEvent.Reason.REMOVE;
            case SET -> FlightTimeChangeEvent.Reason.SET;
            case RESET -> FlightTimeChangeEvent.Reason.RESET;
        };
    }

    private static long saturatedAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------ permissions

    @Override
    public boolean hasUnlimited(Player player) {
        Settings s = settings.get();
        if (s.flight().unlimitedForOp() && player.isOp()) {
            return true;
        }
        return granted(player, PERM_UNLIMITED)
                || (s.compat().wflyPermissions() && granted(player, PERM_WFLY_UNLIMITED));
    }

    @Override
    public boolean bypassesRules(Player player) {
        return granted(player, PERM_BYPASS_RULES)
                || (settings.get().compat().wflyPermissions() && granted(player, PERM_WFLY_BYPASS));
    }

    /**
     * Un OP n'hérite pas implicitement de la permission : elle doit lui être explicitement accordée.
     * Partagé avec le moteur de règles pour garder une seule logique de contournement.
     *
     * @param player     joueur
     * @param permission nœud de permission
     * @return {@code true} si la permission est accordée (explicitement pour un OP)
     */
    public static boolean granted(Player player, String permission) {
        if (!player.hasPermission(permission)) {
            return false;
        }
        return !player.isOp() || player.isPermissionSet(permission);
    }

    // ------------------------------------------------------------------ état

    @Override
    public @Nullable FlightProfile profile(UUID uuid) {
        return storage.cached(uuid);
    }

    @Override
    public boolean isSuspendedByRule(UUID uuid) {
        FlightProfile profile = storage.cached(uuid);
        return profile != null && profile.isSuspended();
    }

    @Override
    public void restore(Player player, FlightProfile profile) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(profile, "profile");
        Settings s = settings.get();
        long now = nowSeconds();
        profile.setSuspendedBy(null);
        profile.setLastMoveEpochMillis(System.currentTimeMillis());
        thresholds.reset(player.getUniqueId());
        boolean unlimited = hasUnlimited(player);

        // Décompte hors-ligne : seulement si le vol était actif à la déconnexion.
        boolean exhaustedOffline = false;
        if (s.decrement().offline() && profile.flightEnabled() && !unlimited) {
            long elapsed = now - profile.lastUpdateEpochSeconds();
            long old = profile.remainingSeconds();
            if (elapsed > 0L && old > 0L) {
                long proposed = TimeMath.subtract(old, elapsed);
                FlightTimeChangeEvent event = new FlightTimeChangeEvent(player.getUniqueId(), player, null,
                        FlightTimeChangeEvent.Reason.OFFLINE_DECREMENT, old, proposed);
                Bukkit.getPluginManager().callEvent(event);
                if (!event.isCancelled()) {
                    profile.setRemainingSeconds(TimeMath.clamp(event.getNewSeconds(), maxSeconds()));
                    exhaustedOffline = profile.remainingSeconds() <= 0L;
                }
            }
        }
        if (timedFly.enabled()) {
            timedFly.applyOffline(profile, now);
        }
        profile.setLastUpdateEpochSeconds(now);
        storage.markDirty(profile);
        speed.apply(player, profile);

        if (!profile.flightEnabled()) {
            return;
        }
        if (exhaustedOffline) {
            // Temps épuisé hors-ligne : sémantique d'expiration (événement, actions on-expire, recharge timed-fly),
            // sauf si un listener a rajouté du temps.
            Bukkit.getPluginManager().callEvent(new FlightExpireEvent(player));
            if (profile.remainingSeconds() <= 0L && !hasUnlimited(player)) {
                abandonWish(player, profile);
                stopFlight(player);
                lang.send(player, "fly.expired");
                actions.runNamed(player, MessagesConfig.KEY_ON_EXPIRE, vars(player, profile));
                return;
            }
        }
        ToggleResult result = doEnable(player, profile, DisableCause.PLAYER, true, true);
        switch (result) {
            case ENABLED -> {
                if (unlimited) {
                    lang.send(player, "fly.enabled-unlimited");
                } else {
                    lang.send(player, "fly.restored", lang.time(formatter.get().format(profile.remainingSeconds())));
                }
            }
            case DENIED_BY_RULE, DENIED_NEARBY -> {
                DisableCause cause = result == ToggleResult.DENIED_BY_RULE ? DisableCause.RULE : DisableCause.NEARBY;
                if (cause == DisableCause.NEARBY || s.flight().autoReactivateAfterRule()) {
                    profile.setSuspendedBy(cause);
                } else {
                    profile.setFlightEnabled(false);
                }
                storage.markDirty(profile);
                stopFlight(player);
                lang.send(player, cause == DisableCause.RULE ? "fly.not-allowed-here" : "fly.nearby-enemy");
            }
            case NO_TIME, TIMED_FLY_COOLDOWN, CANCELLED, NOT_LOADED -> {
                abandonWish(player, profile);
                stopFlight(player);
                if (result == ToggleResult.NO_TIME) {
                    lang.send(player, "fly.no-time");
                }
            }
            default -> {
                // ALREADY / DISABLED : impossible avec force=true.
            }
        }
    }

    // ------------------------------------------------------------------ physique du vol

    /**
     * Applique au joueur l'état « vol autorisé » : {@code allowFlight}, {@code flying} s'il est en l'air,
     * vitesse du profil. Utilisé à l'activation et pour réappliquer l'état (changement de monde/mode).
     *
     * @param player  joueur
     * @param profile son profil
     */
    public void startFlight(Player player, FlightProfile profile) {
        player.setAllowFlight(true);
        if (DefaultSafeLanding.isAirborne(player)) {
            player.setFlying(true);
        }
        speed.apply(player, profile);
    }

    /**
     * Coupe physiquement le vol (sauf creative/spectator) puis atterrissage sécurisé si configuré.
     *
     * @param player joueur
     */
    public void stopFlight(Player player) {
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return;
        }
        boolean airborne = player.isFlying() || DefaultSafeLanding.isAirborne(player);
        player.setFlying(false);
        player.setAllowFlight(false);
        if (airborne && settings.get().flight().tpOnFloorWhenDisabled()) {
            landing.land(player);
        }
    }

    // ------------------------------------------------------------------ utilitaires

    /**
     * @param player  joueur
     * @param profile son profil
     * @return contexte d'évaluation des règles
     */
    public RuleContext context(Player player, FlightProfile profile) {
        return new RuleContext(player, profile, placeholders);
    }

    /**
     * Variables d'actions (SPEC §6), identiques pour toutes les listes d'actions : {@code %remaining%} vaut la
     * durée <b>formatée</b> (comme {@code commands-time-remaining} ; tags retirés dans une commande) et
     * {@code %remaining_seconds%} le nombre entier de secondes (valeur numérique sûre dans une commande console).
     * {@code %player%} et {@code %uuid%} sont ajoutées par l'exécuteur.
     *
     * @param player  joueur (pour l'affichage « illimité »)
     * @param profile profil
     * @return variables
     */
    public Map<String, String> vars(Player player, FlightProfile profile) {
        long seconds = profile.remainingSeconds();
        return Map.of(Action.VAR_REMAINING, formatter.get().format(seconds, hasUnlimited(player)),
                Action.VAR_REMAINING_SECONDS, Long.toString(seconds));
    }

    /**
     * Refus d'activation par une règle : le message est toujours envoyé, mais les actions
     * ({@code not-allowed-here} et celles de la règle, potentiellement des commandes console) ne sont
     * exécutées qu'une fois par joueur et par règle sur {@link #DENIED_ACTIONS_WINDOW_MILLIS} (anti-spam).
     */
    private void notifyDenied(Player player, FlightProfile profile, RuleDecision decision) {
        lang.send(player, "fly.not-allowed-here");
        Rule matched = decision.matched();
        String ruleName = matched == null ? "" : matched.name();
        long nowMillis = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        DeniedNotice last = deniedNotices.get(id);
        if (last != null && last.rule().equals(ruleName)
                && nowMillis - last.atMillis() < DENIED_ACTIONS_WINDOW_MILLIS) {
            return;
        }
        if (deniedNotices.size() >= DENIED_NOTICES_PRUNE_SIZE) {
            deniedNotices.values().removeIf(n -> nowMillis - n.atMillis() >= DENIED_ACTIONS_WINDOW_MILLIS);
        }
        deniedNotices.put(id, new DeniedNotice(ruleName, nowMillis));
        actions.runNamed(player, MessagesConfig.KEY_NOT_ALLOWED_HERE, vars(player, profile));
        runRuleActions(player, decision);
    }

    private void runRuleActions(Player player, RuleDecision decision) {
        Rule matched = decision.matched();
        if (matched == null) {
            return;
        }
        List<Action> list = matched.actions();
        if (!list.isEmpty()) {
            FlightProfile profile = storage.cached(player.getUniqueId());
            actions.run(player, list, profile == null ? Map.of() : vars(player, profile));
        }
    }

    private long maxSeconds() {
        return Math.max(0L, settings.get().limits().maxSeconds());
    }

    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            storage.mainThreadExecutor().execute(task);
        }
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
