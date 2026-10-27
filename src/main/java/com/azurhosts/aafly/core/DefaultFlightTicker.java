package com.azurhosts.aafly.core;

import com.azurhosts.aafly.action.TimeThresholdNotifier;
import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import com.azurhosts.aafly.rules.RuleContext;
import com.azurhosts.aafly.rules.RuleDecision;
import com.azurhosts.aafly.rules.RuleEngine;
import com.azurhosts.aafly.storage.StorageManager;
import com.azurhosts.aafly.time.TimeFormatter;
import com.azurhosts.aafly.timedfly.TimedFlyService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Tâche synchrone unique, exécutée toutes les 20 ticks (SPEC §4 « FlightTicker »). Pour chaque profil en
 * ligne dont le vol est voulu :
 * <ol>
 *   <li>vol suspendu (règle / ennemi proche) : tentative de réactivation silencieuse si autorisé ;</li>
 *   <li>{@code fly-rules} (décision mise en cache par le moteur pendant {@code check-interval}) : DENY → suspension ;</li>
 *   <li>PVP : ennemi proche (tous les {@code pvp.check-interval-seconds}) → suspension ;</li>
 *   <li>illimité : pas de décompte (action bar éventuelle) ;</li>
 *   <li>décompte de 1 s selon {@link DecrementPolicy} (mode, immobilité, bypass, {@code decrement-rules}) ;</li>
 *   <li>seuils {@code commands-time-remaining}, expiration à 0, action bar.</li>
 * </ol>
 * Coût O(profils en ligne) pour un simple test de drapeau, puis O(joueurs en vol) pour le traitement.
 * Une erreur sur un joueur est journalisée et n'interrompt pas les autres. Le temps n'est jamais décompté
 * en creative/spectator ni si un autre plugin a retiré {@code allowFlight}.
 */
public final class DefaultFlightTicker implements FlightTicker {

    private static final long PERIOD_TICKS = 20L;
    /** Fréquence (en secondes) du rechargement timed-fly des joueurs au sol. */
    private static final long TIMED_FLY_REFILL_EVERY = 5L;
    /** Taille maximale du cache des durées rendues. */
    private static final int TIME_CACHE_SIZE = 256;
    private static final TagResolver[] NO_RESOLVERS = new TagResolver[0];
    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_\\-.:]{1,64})%");

    private final Plugin plugin;
    private final Logger logger;
    private final Supplier<Settings> settings;
    private final Lang lang;
    private final Supplier<TimeFormatter> formatter;
    private final StorageManager storage;
    private final DefaultFlightService service;
    private final RuleEngine rules;
    private final PlaceholderBridge placeholders;
    private final TimeThresholdNotifier thresholds;
    private final TimedFlyService timedFly;
    private final NearbyEnemyDetector nearby;

    private @Nullable BukkitTask task;
    private long ticks;
    private long lastTickNanos;
    private long averageTickNanos;
    private int trackedPlayers;
    private volatile Stats stats = Stats.EMPTY;
    private boolean errorLogged;

    /** Template d'action bar découpé (recalculé seulement quand la chaîne de config ou PAPI change). */
    private @Nullable CompiledBar compiledBar;
    /** Réglages pour lesquels {@link #timeComponents} est valide (comparaison par référence). */
    private @Nullable Settings timeCacheSettings;
    /** Petit cache borné « durée formatée → Component » (temps figés au sol, illimité, valeurs partagées). */
    private final Map<String, Component> timeComponents = new HashMap<>(64);

    /**
     * Template d'action bar pré-compilé.
     *
     * @param template     chaîne source (comparée par référence pour l'invalidation)
     * @param papi         disponibilité de PlaceholderAPI au moment du découpage
     * @param mini         chaîne MiniMessage réécrite ({@code <aafly_time>}, {@code <aafly_ph_N>})
     * @param papiTokens   placeholders PAPI bruts ({@code %x%}), l'index N correspondant à {@code <aafly_ph_N>}
     * @param hasTime      {@code true} si le template affiche la durée restante
     */
    private record CompiledBar(String template, boolean papi, String mini, List<String> papiTokens, boolean hasTime) {
    }

    /**
     * @param plugin       plugin (planification)
     * @param settings     réglages courants
     * @param lang         rendu des messages
     * @param formatter    formateur de durées courant
     * @param storage      stockage (profils en ligne)
     * @param service      service de vol
     * @param rules        moteur de règles
     * @param placeholders pont PlaceholderAPI
     * @param thresholds   notificateur de seuils
     * @param timedFly     service timed-fly
     * @param nearby       détection d'ennemis proches
     */
    public DefaultFlightTicker(Plugin plugin, Supplier<Settings> settings, Lang lang, Supplier<TimeFormatter> formatter,
                               StorageManager storage, DefaultFlightService service, RuleEngine rules,
                               PlaceholderBridge placeholders, TimeThresholdNotifier thresholds,
                               TimedFlyService timedFly, NearbyEnemyDetector nearby) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.service = Objects.requireNonNull(service, "service");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.placeholders = Objects.requireNonNull(placeholders, "placeholders");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
        this.timedFly = Objects.requireNonNull(timedFly, "timedFly");
        this.nearby = Objects.requireNonNull(nearby, "nearby");
    }

    @Override
    public void start() {
        if (task != null && !task.isCancelled()) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::run, PERIOD_TICKS, PERIOD_TICKS);
    }

    @Override
    public void stop() {
        BukkitTask t = task;
        task = null;
        if (t != null) {
            t.cancel();
        }
    }

    @Override
    public boolean running() {
        BukkitTask t = task;
        return t != null && !t.isCancelled();
    }

    @Override
    public Stats stats() {
        return stats;
    }

    // ------------------------------------------------------------------ exécution

    private void run() {
        long start = System.nanoTime();
        ticks++;
        Settings s = settings.get();
        long now = System.currentTimeMillis();
        long nowSeconds = now / 1000L;
        long pvpInterval = Math.max(1, s.pvp().checkIntervalSeconds());
        boolean pvpTick = s.pvp().enabled() && ticks % pvpInterval == 0L;
        boolean refillTick = timedFly.enabled() && ticks % TIMED_FLY_REFILL_EVERY == 0L;
        int tracked = 0;

        List<FlightProfile> snapshot = new ArrayList<>(storage.onlineProfiles());
        for (FlightProfile profile : snapshot) {
            if (!profile.flightEnabled() && !refillTick) {
                continue;
            }
            Player player = Bukkit.getPlayer(profile.uuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            try {
                if (!profile.flightEnabled()) {
                    // Timed-fly : recharge périodique des joueurs au sol dont la recharge est écoulée.
                    if (timedFly.refillIfReady(player, profile, nowSeconds)) {
                        lang.send(player, "timed-fly.refilled",
                                lang.time(formatter.get().format(profile.remainingSeconds())));
                    }
                    continue;
                }
                tracked++;
                tickPlayer(player, profile, s, now, nowSeconds, pvpTick);
            } catch (RuntimeException e) {
                if (!errorLogged) {
                    errorLogged = true;
                    logger.log(Level.SEVERE, "Erreur du ticker de vol pour " + profile.uuid()
                            + " (les erreurs suivantes ne seront plus détaillées)", e);
                } else if (s.debug()) {
                    logger.log(Level.WARNING, "Erreur du ticker de vol pour " + profile.uuid(), e);
                }
            }
        }

        long elapsed = System.nanoTime() - start;
        lastTickNanos = elapsed;
        averageTickNanos = averageTickNanos == 0L ? elapsed : (averageTickNanos * 15L + elapsed) / 16L;
        trackedPlayers = tracked;
        stats = new Stats(ticks, lastTickNanos, averageTickNanos, trackedPlayers);
    }

    private void tickPlayer(Player player, FlightProfile profile, Settings s, long nowMillis, long nowSeconds,
                            boolean pvpTick) {
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return;
        }
        boolean bypass = service.bypassesRules(player);
        boolean unlimited = service.hasUnlimited(player);

        if (profile.isSuspended()) {
            tickSuspended(player, profile, s, unlimited, bypass, pvpTick);
            return;
        }

        // Un seul contexte par tick : les placeholders PAPI partagés entre fly-rules et decrement-rules
        // ne sont résolus qu'une fois.
        RuleContext context = service.context(player, profile);
        if (!bypass) {
            RuleDecision decision = rules.evaluateFly(context, false);
            if (!decision.allowed()) {
                service.suspendByRule(player, decision);
                return;
            }
        }
        // La détection PVP (SPEC §4) est distincte des règles : aafly.bypass.rules n'en exempte pas.
        if (pvpTick && nearby.hasEnemyNearby(player)) {
            service.disable(player, DisableCause.NEARBY, false);
            return;
        }

        if (unlimited) {
            sendActionBar(player, profile, s, true);
            return;
        }
        if (profile.remainingSeconds() <= 0L) {
            service.expire(player);
            return;
        }
        if (shouldDecrement(player, profile, s, bypass, nowMillis, context)) {
            long remaining = profile.remainingSeconds() - 1L;
            profile.setRemainingSeconds(remaining);
            profile.setLastUpdateEpochSeconds(nowSeconds);
            storage.markDirty(profile);
            thresholds.onTick(player, profile.remainingSeconds());
            if (profile.remainingSeconds() <= 0L) {
                service.expire(player);
                return;
            }
        }
        sendActionBar(player, profile, s, false);
    }

    private boolean shouldDecrement(Player player, FlightProfile profile, Settings s, boolean bypass, long nowMillis,
                                    RuleContext context) {
        Settings.DecrementSection d = s.decrement();
        // decrement-rules évaluées paresseusement (seulement si les tests bon marché n'ont pas déjà tranché),
        // avec le contournement déjà calculé pour ce tick (aucune vérification de permission supplémentaire).
        return DecrementPolicy.shouldDecrement(d.mode(), player.getAllowFlight(), player.isFlying(),
                d.disableWhenStatic(), d.staticDelaySeconds(), profile.lastMoveEpochMillis(), nowMillis,
                bypass, s.rules().bypassNoDecrement(),
                () -> !rules.decrementRules().isEmpty() && rules.shouldSkipDecrement(context, bypass));
    }

    private void tickSuspended(Player player, FlightProfile profile, Settings s, boolean unlimited, boolean bypass,
                               boolean pvpTick) {
        boolean nearbySuspension = profile.suspendedBy() == DisableCause.NEARBY;
        // Une suspension « ennemi proche » est toujours levée quand le joueur est libre (SPEC §4) ;
        // une suspension par règle ne l'est que si flight.auto-reactivate-after-rule est actif.
        if (!nearbySuspension && !s.flight().autoReactivateAfterRule()) {
            // Suspension par règle alors que la réactivation automatique est (désormais, ex. après un reload)
            // désactivée : le souhait de vol est abandonné (vol déjà coupé physiquement, recharge timed-fly).
            service.abandonWish(player, profile);
            return;
        }
        if (!unlimited && !timedFly.enabled() && profile.remainingSeconds() <= 0L) {
            profile.setFlightEnabled(false);
            profile.setSuspendedBy(null);
            storage.markDirty(profile);
            return;
        }
        DisableCause cause = Objects.requireNonNullElse(profile.suspendedBy(), DisableCause.RULE);
        if (s.pvp().enabled() && !pvpTick) {
            // La présence d'ennemis n'est revérifiée qu'au rythme pvp.check-interval-seconds (bypass compris).
            return;
        }
        if (!bypass) {
            RuleDecision decision = rules.evaluateFly(service.context(player, profile), false);
            if (!decision.allowed()) {
                if (nearbySuspension && !s.flight().autoReactivateAfterRule()) {
                    // Interdit par une règle sans réactivation automatique : le vol est arrêté définitivement.
                    service.suspendByRule(player, decision);
                }
                return;
            }
        }
        ToggleResult result = service.enable(player, cause, true);
        switch (result) {
            case ENABLED -> {
                // SPEC §4 étape 6 : réactivation silencieuse (aucun message à la sortie d'une zone ou d'un combat).
            }
            // Souhait de vol abandonné : en timed-fly, la recharge démarre (jamais de recrédit immédiat).
            case NO_TIME, TIMED_FLY_COOLDOWN, CANCELLED -> service.abandonWish(player, profile);
            default -> {
                // Toujours interdit (ennemi proche, règle) : on réessaiera.
            }
        }
    }

    // ------------------------------------------------------------------ action bar

    private void sendActionBar(Player player, FlightProfile profile, Settings s, boolean unlimited) {
        Settings.ActionBarSection bar = s.actionbar();
        if (!bar.enabled() || (unlimited && bar.hideIfUnlimited()) || bar.message().isEmpty()) {
            return;
        }
        String formatted = formatter.get().format(profile.remainingSeconds(), unlimited);
        player.sendActionBar(render(player, s, bar.message(), formatted));
    }

    /**
     * Rend le message d'action bar : {@code %aafly_remaining%} / {@code %remaining%} deviennent la durée formatée
     * (chaîne de configuration, fiable) ; les autres placeholders PAPI sont résolus un par un et insérés comme
     * <b>texte littéral</b> (jamais interprétés comme MiniMessage). Le découpage du template est mis en cache :
     * un tick ne fait qu'une désérialisation MiniMessage (plus celle de la durée si elle n'est pas en cache).
     */
    private Component render(Player player, Settings s, String template, String formatted) {
        CompiledBar bar = compiled(template);
        List<String> tokens = bar.papiTokens();
        int count = (bar.hasTime() ? 1 : 0) + tokens.size();
        if (count == 0) {
            return lang.parse(bar.mini(), NO_RESOLVERS);
        }
        TagResolver[] resolvers = new TagResolver[count];
        int r = 0;
        if (bar.hasTime()) {
            resolvers[r++] = Lang.component("aafly_time", timeComponent(s, formatted));
        }
        for (int i = 0; i < tokens.size(); i++) {
            resolvers[r++] = Lang.unparsed("aafly_ph_" + i, placeholders.apply(player, tokens.get(i)));
        }
        return lang.parse(bar.mini(), resolvers);
    }

    /** Découpe le template une seule fois par valeur de configuration (et par état de PlaceholderAPI). */
    private CompiledBar compiled(String template) {
        boolean papi = placeholders.available();
        CompiledBar cached = compiledBar;
        if (cached != null && cached.template() == template && cached.papi() == papi) {
            return cached;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 16);
        List<String> tokens = new ArrayList<>(2);
        boolean hasTime = false;
        int last = 0;
        while (m.find()) {
            out.append(template, last, m.start());
            last = m.end();
            String name = m.group(1);
            if ("aafly_remaining".equals(name) || "remaining".equals(name) || "wfly_fly_remaining".equals(name)) {
                out.append("<aafly_time>");
                hasTime = true;
            } else if (papi) {
                out.append("<aafly_ph_").append(tokens.size()).append('>');
                tokens.add(m.group());
            } else {
                out.append(m.group());
            }
        }
        out.append(template, last, template.length());
        CompiledBar built = new CompiledBar(template, papi, out.toString(), List.copyOf(tokens), hasTime);
        compiledBar = built;
        return built;
    }

    /** Durée formatée rendue en Component (texte brut si aucun tag, sinon MiniMessage), avec cache borné. */
    private Component timeComponent(Settings s, String formatted) {
        if (timeCacheSettings != s) {
            timeComponents.clear();
            timeCacheSettings = s;
        }
        Component cached = timeComponents.get(formatted);
        if (cached != null) {
            return cached;
        }
        Component built = formatted.indexOf('<') < 0 && formatted.indexOf('&') < 0 && formatted.indexOf('\u00a7') < 0
                ? Component.text(formatted)
                : lang.parse(formatted);
        if (timeComponents.size() >= TIME_CACHE_SIZE) {
            timeComponents.clear();
        }
        timeComponents.put(formatted, built);
        return built;
    }
}
