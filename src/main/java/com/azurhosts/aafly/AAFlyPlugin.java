package com.azurhosts.aafly;

import com.azurhosts.aafly.action.ActionExecutor;
import com.azurhosts.aafly.action.ActionParser;
import com.azurhosts.aafly.action.BukkitActionRuntime;
import com.azurhosts.aafly.action.DefaultActionExecutor;
import com.azurhosts.aafly.action.DefaultActionParser;
import com.azurhosts.aafly.action.DefaultTimeThresholdNotifier;
import com.azurhosts.aafly.action.TimeThresholdNotifier;
import com.azurhosts.aafly.api.AAFlyApi;
import com.azurhosts.aafly.apiimpl.DefaultAAFlyApi;
import com.azurhosts.aafly.command.Commands;
import com.azurhosts.aafly.config.ConfigException;
import com.azurhosts.aafly.config.ConfigManager;
import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.DefaultFlightService;
import com.azurhosts.aafly.core.DefaultFlightTicker;
import com.azurhosts.aafly.core.DefaultFlySpeedService;
import com.azurhosts.aafly.core.DefaultSafeLanding;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.FlightService;
import com.azurhosts.aafly.core.FlightTicker;
import com.azurhosts.aafly.core.FlySpeedService;
import com.azurhosts.aafly.core.NearbyEnemyDetector;
import com.azurhosts.aafly.core.SafeLanding;
import com.azurhosts.aafly.hook.LuckPermsHook;
import com.azurhosts.aafly.hook.PapiHook;
import com.azurhosts.aafly.listener.ConnectionListener;
import com.azurhosts.aafly.listener.FallDamageListener;
import com.azurhosts.aafly.listener.GameModeListener;
import com.azurhosts.aafly.listener.MoveListener;
import com.azurhosts.aafly.listener.RespawnListener;
import com.azurhosts.aafly.listener.WorldChangeListener;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import com.azurhosts.aafly.placeholder.Placeholders;
import com.azurhosts.aafly.rules.ConditionRegistry;
import com.azurhosts.aafly.rules.DefaultConditionRegistry;
import com.azurhosts.aafly.rules.DefaultRuleEngine;
import com.azurhosts.aafly.rules.RuleEngine;
import com.azurhosts.aafly.storage.DefaultStorageManager;
import com.azurhosts.aafly.storage.StorageException;
import com.azurhosts.aafly.storage.StorageManager;
import com.azurhosts.aafly.time.TimeFormatter;
import com.azurhosts.aafly.timedfly.DefaultGroupResolver;
import com.azurhosts.aafly.timedfly.DefaultTimedFlyService;
import com.azurhosts.aafly.timedfly.GroupResolver;
import com.azurhosts.aafly.timedfly.TimedFlyService;
import com.azurhosts.aafly.token.DefaultFlyTokenService;
import com.azurhosts.aafly.token.FlyTokenService;
import com.azurhosts.aafly.token.TokenListener;
import com.azurhosts.aafly.token.TokenSigner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Point d'entrée d'AAFly — vol temporisé par AzurHOSTS.
 * Developped by AzurHOSTS.com and powered by AzurWare.fr.
 *
 * <p>Cycle de vie : {@link #onEnable()} charge et valide la configuration ({@link ConfigManager}) puis
 * appelle {@link #wire()} qui instancie et relie les services. Toute erreur désactive proprement le
 * plugin (jamais d'état à moitié chargé). {@link #onDisable()} arrête le ticker puis vide le stockage
 * de façon synchrone (timeout 10 s).
 */
public final class AAFlyPlugin extends JavaPlugin {

    private @Nullable ConfigManager configManager;
    private @Nullable Lang lang;
    private volatile @Nullable TimeFormatter timeFormatter;
    private @Nullable PlaceholderBridge placeholders;
    private @Nullable StorageManager storage;
    private @Nullable FlightService flightService;
    private @Nullable FlightTicker ticker;
    private @Nullable SafeLanding safeLanding;
    private @Nullable FlySpeedService flySpeed;
    private @Nullable ConditionRegistry conditionRegistry;
    private @Nullable RuleEngine ruleEngine;
    private @Nullable ActionParser actionParser;
    private @Nullable ActionExecutor actionExecutor;
    private @Nullable TimeThresholdNotifier thresholdNotifier;
    private @Nullable TokenSigner tokenSigner;
    private @Nullable FlyTokenService tokenService;
    private @Nullable GroupResolver groupResolver;
    private @Nullable TimedFlyService timedFly;
    private @Nullable AAFlyApi api;
    // Implémentations concrètes conservées pour le rechargement et l'arrêt (méthodes hors contrats).
    private @Nullable BukkitActionRuntime actionRuntime;
    private @Nullable DefaultActionParser defaultActionParser;
    private @Nullable DefaultActionExecutor defaultActionExecutor;
    private @Nullable DefaultFlightService defaultFlightService;
    private @Nullable Placeholders papi;
    private @Nullable NearbyEnemyDetector nearbyDetector;
    private Commands.@Nullable Registered commands;

    @Override
    public void onEnable() {
        try {
            ConfigManager cm = new ConfigManager(getDataFolder().toPath(), getLogger(), this::getResource);
            cm.load();
            this.configManager = cm;
            this.lang = new Lang(cm.messages(), getLogger());
            this.timeFormatter = new TimeFormatter(cm.settings().format());
            this.placeholders = new PlaceholderBridge(getLogger());
            wire();
            getLogger().info("AAFly " + getPluginMeta().getVersion() + " activé — par AzurHOSTS (azurhosts.com).");
        } catch (ConfigException e) {
            getLogger().severe("Configuration invalide, AAFly est désactivé : " + e.getMessage());
            shutdownServices();
            getServer().getPluginManager().disablePlugin(this);
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Erreur au démarrage, AAFly est désactivé.", e);
            shutdownServices();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /**
     * Instancie et relie les services, dans l'ordre : secret des jetons → stockage → pont de placeholders →
     * règles/actions → services cœur (+ timed-fly) → jetons → listeners → commandes → ticker →
     * PlaceholderAPI → API publique → joueurs déjà connectés. Toute exception remonte à {@link #onEnable()}
     * qui désactive proprement le plugin (jamais d'état à moitié chargé).
     *
     * @throws ConfigException si une ressource requise (stockage, secret des jetons) est inutilisable
     */
    private void wire() throws ConfigException {
        Supplier<Settings> st = this::settings;
        Supplier<TimeFormatter> tf = this::timeFormatter;
        Settings settings = settings();
        ConfigManager cm = configManager();
        Lang lng = lang();
        PlaceholderBridge bridge = placeholders();
        PluginManager pm = getServer().getPluginManager();

        // 1. Secret des jetons (échec rapide si le dossier de données est inutilisable).
        try {
            this.tokenSigner = TokenSigner.loadOrCreate(getDataFolder().toPath().resolve(TokenSigner.SECRET_FILE_NAME));
        } catch (IOException e) {
            throw new ConfigException("Secret des jetons (" + TokenSigner.SECRET_FILE_NAME
                    + ") illisible ou impossible à créer : " + e.getMessage(), e);
        }

        // 2. Stockage : assigné avant le démarrage pour que shutdownServices() ferme le pool en cas d'échec.
        try {
            DefaultStorageManager dsm = new DefaultStorageManager(this, settings.storage(),
                    () -> settings().flight().defaultSpeed());
            this.storage = dsm;
            dsm.startSync(); // bloquant (30 s max) ; jamais start().join() sur le main thread
        } catch (StorageException e) {
            throw new ConfigException("Stockage indisponible : " + e.getMessage(), e);
        }
        StorageManager sm = storage();

        // 3. Pont PlaceholderAPI (détection initiale ; suivi activation/désactivation par PapiHook).
        bridge.refresh();

        // 4. Règles et actions.
        DefaultConditionRegistry conditions = new DefaultConditionRegistry();
        this.conditionRegistry = conditions;
        BukkitActionRuntime runtime = new BukkitActionRuntime(this, lng, bridge, this::formattedRemaining);
        this.actionRuntime = runtime;
        DefaultActionParser parser = new DefaultActionParser(runtime, getLogger(),
                settings.actions().allowPlayerCommands());
        this.defaultActionParser = parser;
        this.actionParser = parser;
        DefaultActionExecutor executor = new DefaultActionExecutor(parser, runtime, cm.messages());
        this.defaultActionExecutor = executor;
        this.actionExecutor = executor;
        DefaultTimeThresholdNotifier thresholds = new DefaultTimeThresholdNotifier(parser, executor,
                seconds -> timeFormatter().format(seconds), cm.messages());
        this.thresholdNotifier = thresholds;
        DefaultRuleEngine rules = new DefaultRuleEngine(conditions, parser, getLogger());
        rules.reload(settings);
        this.ruleEngine = rules;

        // 5. Services cœur ; le timed-fly est créé avant le service de vol qui en dépend.
        DefaultSafeLanding landing = new DefaultSafeLanding(() -> settings().flight().safeLanding().maxScan());
        this.safeLanding = landing;
        DefaultFlySpeedService speed = new DefaultFlySpeedService(sm, st);
        this.flySpeed = speed;
        LuckPermsHook luckPerms = LuckPermsHook.isPresent() ? new LuckPermsHook(getLogger()) : null;
        DefaultGroupResolver groups = new DefaultGroupResolver(luckPerms);
        this.groupResolver = groups;
        DefaultTimedFlyService timed = new DefaultTimedFlyService(settings, groups);
        this.timedFly = timed;
        // A6 : résolveurs tiers (API) puis résolveur par défaut (pvp.bypass-placeholders).
        NearbyEnemyDetector nearby = new NearbyEnemyDetector(st,
                new com.azurhosts.aafly.core.ThreatResolverRegistry(getLogger()),
                new com.azurhosts.aafly.core.BuiltinThreatResolver(st, bridge));
        this.nearbyDetector = nearby;
        DefaultFlightService fs = new DefaultFlightService(getLogger(), st, lng, tf, sm, rules, bridge, executor,
                thresholds, timed, landing, speed, nearby);
        this.defaultFlightService = fs;
        this.flightService = fs;

        // 6. Jetons de vol.
        DefaultFlyTokenService tokens = new DefaultFlyTokenService(this, tokenSigner());
        this.tokenService = tokens;

        // 7. Listeners.
        ConnectionListener connection = new ConnectionListener(this, sm, fs, landing, rules, thresholds);
        pm.registerEvents(connection, this);
        pm.registerEvents(new WorldChangeListener(st, sm, fs, rules), this);
        pm.registerEvents(new GameModeListener(this, sm, fs), this);
        pm.registerEvents(new RespawnListener(this, sm, fs, rules), this);
        pm.registerEvents(new FallDamageListener(landing), this);
        pm.registerEvents(new MoveListener(st, sm, landing), this);
        pm.registerEvents(new com.azurhosts.aafly.listener.ThreatListener(fs, nearby, getLogger()), this);
        pm.registerEvents(new TokenListener(this, tokens), this);
        pm.registerEvents(new QuitCleanup(), this);

        // 8. Commandes (une seule fois : gestionnaire de cycle de vie Paper).
        this.commands = Commands.registerAll(this);

        // 9. Ticker : une seule tâche synchrone toutes les 20 ticks.
        DefaultFlightTicker flightTicker = new DefaultFlightTicker(this, st, lng, tf, sm, fs, rules, bridge,
                thresholds, timed, nearby);
        this.ticker = flightTicker;
        flightTicker.start();

        // 10. PlaceholderAPI : expansions aafly (+ wfly si compat) maintenant, ou dès l'activation de PAPI.
        PapiHook papiHook = new PapiHook(this, bridge, this::registerExpansions);
        pm.registerEvents(papiHook, this);
        papiHook.init();

        // 11. API publique (ServicesManager), après le service de vol et le registre de conditions.
        // Crédits externes : api.allow-external-grants (SPEC-phase2 B3, défaut true), relu à chaque appel.
        this.api = DefaultAAFlyApi.register(this, nearby.registry(), () -> settings().api().allowExternalGrants());

        // 12. Joueurs déjà connectés (cas d'un /reload serveur).
        connection.loadOnlinePlayers();
    }

    /** Enregistre les expansions PlaceholderAPI ; idempotent (rappelé à chaque activation de PAPI). */
    private void registerExpansions() {
        Placeholders current = papi;
        if (current != null && current.active()) {
            current.reload();
            return;
        }
        this.papi = Placeholders.registerIfPresent(this);
    }

    /**
     * Temps restant formaté d'un joueur, pour {@code %remaining%} des actions.
     *
     * @param player joueur
     * @return durée formatée, ou {@code null} si le profil n'est pas chargé
     */
    private @Nullable String formattedRemaining(Player player) {
        StorageManager sm = storage;
        FlightService fs = flightService;
        TimeFormatter tf = timeFormatter;
        if (sm == null || fs == null || tf == null) {
            return null;
        }
        FlightProfile profile = sm.cached(player.getUniqueId());
        if (profile == null) {
            return null;
        }
        return tf.format(profile.remainingSeconds(), fs.hasUnlimited(player));
    }

    /** Libère l'état runtime par joueur qui n'appartient à aucun listener dédié (verrous de /fly give). */
    private final class QuitCleanup implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            Commands.Registered cmds = commands;
            if (cmds != null) {
                cmds.fly().forget(event.getPlayer().getUniqueId());
            }
        }
    }

    @Override
    public void onDisable() {
        shutdownServices();
    }

    /**
     * Arrêt propre et idempotent : ticker, expansions PlaceholderAPI, API, coupure du vol accordé par AAFly
     * (pas de vol gratuit plugin désactivé ; l'état voulu reste persisté et sera restauré), puis flush
     * synchrone du stockage (timeout 10 s), arrêt de l'executor {@code AAFly-IO} et fermeture du pool.
     */
    private void shutdownServices() {
        try {
            if (ticker != null) {
                ticker.stop();
            }
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Arrêt du ticker en erreur", e);
        }
        Placeholders p = papi;
        papi = null;
        if (p != null) {
            p.unregister();
        }
        if (api != null) {
            getServer().getServicesManager().unregisterAll(this);
        }
        revokeFlight();
        try {
            if (storage != null) {
                storage.shutdown();
            }
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Sauvegarde finale des profils en erreur", e);
        }
        ticker = null;
        storage = null;
        api = null;
        flightService = null;
        defaultFlightService = null;
        nearbyDetector = null;
        tokenService = null;
        commands = null;
    }

    /**
     * Retire physiquement, aux joueurs en ligne, <b>uniquement</b> les vols accordés par AAFly (SPEC-phase2 B3 :
     * un vol accordé par AAItems ou un autre plugin n'est jamais retiré ; creative/spectator jamais touchés).
     */
    private void revokeFlight() {
        DefaultFlightService fs = defaultFlightService;
        if (fs == null) {
            return;
        }
        try {
            fs.revokeOwnedFlights();
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Coupure des vols AAFly à l'arrêt en erreur", e);
        }
    }

    /**
     * Recharge config.yml, messages.yml et les règles (le stockage n'est pas rechargé).
     * Atomique : en cas d'erreur, l'ancienne configuration reste active.
     *
     * @throws ConfigException si la nouvelle configuration est invalide
     */
    public void reload() throws ConfigException {
        ConfigManager cm = configManager;
        if (cm == null) {
            throw new ConfigException("AAFly n'est pas initialisé.");
        }
        cm.reload();
        Settings s = cm.settings();
        // Ordre strict : allow-player-commands doit être à jour avant toute ré-analyse d'actions.
        if (defaultActionParser != null) {
            defaultActionParser.reload(s);
        }
        if (lang != null) {
            lang.reload(cm.messages());
        }
        this.timeFormatter = new TimeFormatter(s.format());
        if (defaultActionExecutor != null) {
            defaultActionExecutor.reload(cm.messages());
        }
        if (thresholdNotifier != null) {
            thresholdNotifier.reload(cm.messages());
        }
        if (placeholders != null) {
            placeholders.refresh();
        }
        if (ruleEngine != null) {
            ruleEngine.reload(s);
        }
        if (timedFly != null) {
            timedFly.reload(s);
        }
        if (papi != null) {
            papi.reload();
        }
        if (actionRuntime != null) {
            actionRuntime.clearWarnings();
        }
        getLogger().info("Configuration rechargée (le stockage n'est pas rechargé à chaud).");
    }

    // ------------------------------------------------------------------ accès aux services

    /**
     * @return réglages courants
     */
    public Settings settings() {
        return requireNonNull(configManager, "ConfigManager").settings();
    }

    public ConfigManager configManager() {
        return requireNonNull(configManager, "ConfigManager");
    }

    public Lang lang() {
        return requireNonNull(lang, "Lang");
    }

    public TimeFormatter timeFormatter() {
        return requireNonNull(timeFormatter, "TimeFormatter");
    }

    public PlaceholderBridge placeholders() {
        return requireNonNull(placeholders, "PlaceholderBridge");
    }

    public StorageManager storage() {
        return requireNonNull(storage, "StorageManager");
    }

    public FlightService flightService() {
        return requireNonNull(flightService, "FlightService");
    }

    public FlightTicker ticker() {
        return requireNonNull(ticker, "FlightTicker");
    }

    public SafeLanding safeLanding() {
        return requireNonNull(safeLanding, "SafeLanding");
    }

    public FlySpeedService flySpeed() {
        return requireNonNull(flySpeed, "FlySpeedService");
    }

    public ConditionRegistry conditionRegistry() {
        return requireNonNull(conditionRegistry, "ConditionRegistry");
    }

    public RuleEngine ruleEngine() {
        return requireNonNull(ruleEngine, "RuleEngine");
    }

    public ActionParser actionParser() {
        return requireNonNull(actionParser, "ActionParser");
    }

    public ActionExecutor actionExecutor() {
        return requireNonNull(actionExecutor, "ActionExecutor");
    }

    public TimeThresholdNotifier thresholdNotifier() {
        return requireNonNull(thresholdNotifier, "TimeThresholdNotifier");
    }

    public TokenSigner tokenSigner() {
        return requireNonNull(tokenSigner, "TokenSigner");
    }

    public FlyTokenService tokenService() {
        return requireNonNull(tokenService, "FlyTokenService");
    }

    public GroupResolver groupResolver() {
        return requireNonNull(groupResolver, "GroupResolver");
    }

    public TimedFlyService timedFly() {
        return requireNonNull(timedFly, "TimedFlyService");
    }

    public AAFlyApi api() {
        return requireNonNull(api, "AAFlyApi");
    }

    /**
     * @return nombre de résolveurs de menace tiers enregistrés via l'API (0 si non initialisé)
     */
    public int threatResolverCount() {
        NearbyEnemyDetector n = nearbyDetector;
        return n == null ? 0 : n.registry().size();
    }

    /**
     * @return nombre de joueurs dont le vol a été accordé par AAFly (SPEC-phase2 B3 ; 0 si non initialisé)
     */
    public int ownedFlightCount() {
        DefaultFlightService fs = defaultFlightService;
        return fs == null ? 0 : fs.ownership().size();
    }

    private static <T> T requireNonNull(@Nullable T value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " n'est pas initialisé (AAFly désactivé ou en cours de démarrage).");
        }
        return value;
    }
}
