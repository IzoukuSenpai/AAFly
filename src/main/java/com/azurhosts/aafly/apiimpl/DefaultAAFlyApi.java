package com.azurhosts.aafly.apiimpl;

import com.azurhosts.aafly.AAFlyPlugin;
import com.azurhosts.aafly.api.AAFlyApi;
import com.azurhosts.aafly.api.ApiVersion;
import com.azurhosts.aafly.api.ConditionFactory;
import com.azurhosts.aafly.api.DisableCause;
import com.azurhosts.aafly.api.GrantRequest;
import com.azurhosts.aafly.api.GrantResult;
import com.azurhosts.aafly.api.ThreatResolver;
import com.azurhosts.aafly.api.events.FlightTimeChangeEvent;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.FlightService;
import com.azurhosts.aafly.core.ThreatResolverRegistry;
import com.azurhosts.aafly.core.TimeChangeResult;
import com.azurhosts.aafly.core.ToggleResult;
import com.azurhosts.aafly.rules.ConditionRegistry;
import com.azurhosts.aafly.time.TimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Implémentation de {@link AAFlyApi} (SPEC-phase2 B2), publiée dans le {@code ServicesManager} de Bukkit.
 * Remplace la {@code FlightApi} de phase 1 (fusionnée : une seule API publique).
 *
 * <p><b>Sécurité :</b> toutes les méthodes exigent le main thread ({@link IllegalStateException} sinon) ; les
 * modifications passent par {@link FlightService} (bornes {@code limits.max-seconds}, timed-fly, événements,
 * règles) ; les sources sont validées par {@link GrantRequest#SOURCE_PATTERN} avant d'être journalisées.
 * Les conditions et résolveurs de menace d'un plugin tiers sont retirés à sa désactivation.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class DefaultAAFlyApi implements AAFlyApi, Listener {

    private final Logger logger;
    private final FlightService service;
    private final ConditionRegistry conditions;
    private final ThreatResolverRegistry threats;
    private final Supplier<TimeFormatter> formatter;
    private final Supplier<Settings> settings;
    private final BooleanSupplier externalGrantsAllowed;
    /** Recharge différée des règles (prochain tick) après un ajout/retrait de type de condition. */
    private final Runnable rulesChanged;
    /** Type de condition (minuscules) → plugin qui l'a enregistré, pour le retrait à sa désactivation. */
    private final Map<String, Plugin> conditionOwners = new ConcurrentHashMap<>();

    /**
     * @param logger                logger du plugin
     * @param service               service de vol
     * @param conditions            registre des conditions
     * @param threats               registre des résolveurs de menace (celui de la détection PVP)
     * @param formatter             formateur de durées courant
     * @param settings              réglages courants (relus à chaque appel : compatible reload)
     * @param externalGrantsAllowed valeur courante de {@code api.allow-external-grants}
     */
    public DefaultAAFlyApi(Logger logger, FlightService service, ConditionRegistry conditions,
                           ThreatResolverRegistry threats, Supplier<TimeFormatter> formatter,
                           Supplier<Settings> settings, BooleanSupplier externalGrantsAllowed) {
        this(logger, service, conditions, threats, formatter, settings, externalGrantsAllowed, () -> { });
    }

    /**
     * @param logger                logger du plugin
     * @param service               service de vol
     * @param conditions            registre des conditions
     * @param threats               registre des résolveurs de menace (celui de la détection PVP)
     * @param formatter             formateur de durées courant
     * @param settings              réglages courants (relus à chaque appel : compatible reload)
     * @param externalGrantsAllowed valeur courante de {@code api.allow-external-grants}
     * @param rulesChanged          appelé (main thread) quand un type de condition est ajouté ou retiré
     */
    public DefaultAAFlyApi(Logger logger, FlightService service, ConditionRegistry conditions,
                           ThreatResolverRegistry threats, Supplier<TimeFormatter> formatter,
                           Supplier<Settings> settings, BooleanSupplier externalGrantsAllowed,
                           Runnable rulesChanged) {
        this.rulesChanged = Objects.requireNonNull(rulesChanged, "rulesChanged");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.service = Objects.requireNonNull(service, "service");
        this.conditions = Objects.requireNonNull(conditions, "conditions");
        this.threats = Objects.requireNonNull(threats, "threats");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.externalGrantsAllowed = Objects.requireNonNull(externalGrantsAllowed, "externalGrantsAllowed");
    }

    /**
     * Crée l'API, l'enregistre dans le {@code ServicesManager} ({@link AAFlyApi}, priorité {@code Normal}) et
     * enregistre son listener de désactivation des plugins. À appeler sur le main thread une fois le service
     * de vol, le registre de conditions et la détection PVP initialisés. Le retrait se fait par
     * {@code getServicesManager().unregisterAll(plugin)} à l'arrêt.
     *
     * @param plugin                plugin AAFly
     * @param threats               registre des résolveurs de menace utilisé par la détection PVP
     * @param externalGrantsAllowed valeur courante de {@code api.allow-external-grants}
     * @return l'API enregistrée
     */
    public static DefaultAAFlyApi register(AAFlyPlugin plugin, ThreatResolverRegistry threats,
                                           BooleanSupplier externalGrantsAllowed) {
        Objects.requireNonNull(plugin, "plugin");
        // Recharge des règles coalescée au tick suivant : un type enregistré s'applique sans /aafly reload, et
        // un type retiré (plugin désactivé) ne laisse aucune référence vers les classes du plugin déchargé.
        java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable rulesChanged = () -> {
            if (plugin.isEnabled() && pending.compareAndSet(false, true)) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    pending.set(false);
                    try {
                        plugin.ruleEngine().reload(plugin.settings());
                    } catch (RuntimeException e) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING,
                                "Rechargement des règles après modification de l'API en erreur", e);
                    }
                });
            }
        };
        DefaultAAFlyApi api = new DefaultAAFlyApi(plugin.getLogger(), plugin.flightService(),
                plugin.conditionRegistry(), threats, plugin::timeFormatter, plugin::settings, externalGrantsAllowed,
                rulesChanged);
        plugin.getServer().getPluginManager().registerEvents(api, plugin);
        plugin.getServer().getServicesManager().register(AAFlyApi.class, api, plugin, ServicePriority.Normal);
        return api;
    }

    // ------------------------------------------------------------------ lecture

    @Override
    public ApiVersion apiVersion() {
        requireMainThread();
        return ApiVersion.CURRENT;
    }

    @Override
    public long remainingSeconds(UUID player) {
        requireMainThread();
        FlightProfile profile = service.profile(Objects.requireNonNull(player, "player"));
        return profile == null ? -1L : profile.remainingSeconds();
    }

    @Override
    public boolean isFlightEnabled(UUID player) {
        requireMainThread();
        FlightProfile profile = service.profile(Objects.requireNonNull(player, "player"));
        // B3 : un vol voulu dont allowFlight a été retiré par un tiers n'est pas « actif ».
        return profile != null && profile.flightEnabled() && !profile.isSuspended() && service.ownsFlight(player);
    }

    @Override
    public boolean isUnlimited(Player player) {
        requireMainThread();
        return service.hasUnlimited(Objects.requireNonNull(player, "player"));
    }

    @Override
    public boolean isSuspended(UUID player) {
        requireMainThread();
        return service.isSuspendedByRule(Objects.requireNonNull(player, "player"));
    }

    @Override
    public String formatTime(long seconds) {
        requireMainThread();
        return formatter.get().format(Math.max(0L, seconds));
    }

    // ------------------------------------------------------------------ crédit

    @Override
    public GrantResult grant(Player player, GrantRequest request) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        String source = requireSource(request.source());
        Settings s = settings.get();
        UUID uuid = player.getUniqueId();
        FlightProfile profile = player.isOnline() ? service.profile(uuid) : null;
        boolean loaded = profile != null;
        // L'illimité n'est évalué que si le profil est chargé (inutile sinon, et évite un appel de permission).
        GrantLogic.Decision decision = GrantLogic.decide(new GrantLogic.Input(request.seconds(), request.mode(),
                request.capSeconds(), s.limits().maxSeconds(), externalGrantsAllowed.getAsBoolean(),
                s.timedFly().enabled(), loaded, loaded && service.hasUnlimited(player),
                loaded ? profile.remainingSeconds() : 0L, loaded && profile.flightEnabled()));
        if (!decision.credit()) {
            debug(s, source, player, request, 0L, decision.status());
            return new GrantResult(decision.status(), decision.remaining());
        }
        long old = profile.remainingSeconds();
        TimeChangeResult change = service.setTime(uuid, decision.targetSeconds(), null,
                FlightTimeChangeEvent.Reason.API, source).getNow(null);
        GrantResult result = toResult(change, old);
        debug(s, source, player, request, result.granted() ? result.remainingSeconds() - old : 0L, result.status());
        if (result.granted() && request.autoEnable() && player.isOnline()) {
            autoEnable(player, source);
        }
        return result;
    }

    /**
     * Activation qui suit un crédit {@code GRANTED} : <b>best-effort</b>. Le crédit est acquis, donc aucune
     * exception ne doit remonter à l'appelant (qui la traduirait en échec et rembourserait un temps déjà crédité).
     * Toujours délégué au service : il renvoie {@code ALREADY} sans message si le vol AAFly est réellement actif
     * et possédé (SPEC-phase2 B3), et réactive le vol si un tiers a retiré {@code allowFlight}. Non silencieux :
     * en cas de refus par une règle, le joueur reçoit « not-allowed-here » ; le temps reste crédité.
     */
    private void autoEnable(Player player, String source) {
        try {
            service.enable(player, DisableCause.API, false);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Activation du vol après un crédit API (source=" + source + ", joueur="
                    + player.getName() + ") en erreur : le temps reste crédité.", e);
        }
    }

    private GrantResult toResult(@Nullable TimeChangeResult change, long old) {
        if (change == null) {
            // Joueur en ligne : le futur est toujours complété immédiatement ; filet de sécurité.
            return new GrantResult(GrantResult.Status.NOT_LOADED, -1L);
        }
        return switch (change.status()) {
            case OK, CLAMPED -> change.newSeconds() > old
                    ? new GrantResult(GrantResult.Status.GRANTED, change.newSeconds())
                    // Un listener a ramené la valeur sous l'ancienne (ou à l'identique) : rien crédité.
                    : new GrantResult(GrantResult.Status.DENIED_BY_RULE, change.newSeconds());
            case CANCELLED -> new GrantResult(GrantResult.Status.DENIED_BY_RULE, old);
            case TIMED_FLY_LOCKED -> new GrantResult(GrantResult.Status.DISABLED_BY_CONFIG, old);
            default -> {
                logger.warning("Crédit de vol API refusé par le service : " + change.status());
                yield new GrantResult(GrantResult.Status.DENIED_BY_RULE, old);
            }
        };
    }

    // ------------------------------------------------------------------ activation

    @Override
    public boolean enable(Player player, String source) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        String src = requireSource(source);
        ToggleResult r = service.enable(player, DisableCause.API, false);
        if (settings.get().debug()) {
            logger.info("[debug] API enable source=" + src + " joueur=" + player.getName() + " résultat=" + r);
        }
        return r == ToggleResult.ENABLED;
    }

    @Override
    public boolean disable(Player player, String source) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        String src = requireSource(source);
        ToggleResult r = service.disable(player, DisableCause.API, false);
        if (settings.get().debug()) {
            logger.info("[debug] API disable source=" + src + " joueur=" + player.getName() + " résultat=" + r);
        }
        return r == ToggleResult.DISABLED;
    }

    // ------------------------------------------------------------------ extensions

    @Override
    public void registerCondition(String type, ConditionFactory factory) {
        requireMainThread();
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        String key = type.trim().toLowerCase(Locale.ROOT);
        // Validation (format, type réservé, doublon) déléguée au registre : IllegalArgumentException.
        conditions.register(key, new ApiConditionAdapter(key, factory, logger));
        Plugin owner = owner(factory.getClass());
        if (owner != null) {
            conditionOwners.put(key, owner);
        }
        logger.info("Type de condition '" + key + "' enregistré" + (owner == null ? "" : " par " + owner.getName())
                + " (règles rechargées au prochain tick).");
        rulesChanged.run();
    }

    @Override
    public void registerThreatResolver(ThreatResolver resolver) {
        requireMainThread();
        threats.register(Objects.requireNonNull(resolver, "resolver"));
    }

    /**
     * Retire les extensions (conditions, résolveurs de menace) d'un plugin désactivé : AAFly n'appelle jamais
     * le code d'un plugin déchargé.
     *
     * @param event désactivation d'un plugin
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin plugin = event.getPlugin();
        int removed = threats.unregisterAll(plugin);
        for (Map.Entry<String, Plugin> e : conditionOwners.entrySet()) {
            if (e.getValue() == plugin && conditionOwners.remove(e.getKey(), plugin)) {
                conditions.unregister(e.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            rulesChanged.run();
            logger.info(removed + " extension(s) de " + plugin.getName() + " retirée(s) de l'API AAFly.");
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private void debug(Settings s, String source, Player player, GrantRequest request, long delta,
                       GrantResult.Status status) {
        if (s.debug()) {
            logger.info("[debug] API grant source=" + source + " joueur=" + player.getName() + " ("
                    + player.getUniqueId() + ") mode=" + request.mode() + " demandé=" + request.seconds()
                    + "s delta=" + delta + "s statut=" + status);
        }
    }

    private static String requireSource(@Nullable String source) {
        if (!GrantRequest.isValidSource(source)) {
            throw new IllegalArgumentException("source invalide (attendu ^[a-z0-9_.:-]{1,32}$)");
        }
        return source;
    }

    private static @Nullable Plugin owner(Class<?> type) {
        try {
            return JavaPlugin.getProvidingPlugin(type);
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException notFromPlugin) {
            return null;
        }
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("L'API AAFly doit être appelée depuis le main thread du serveur.");
        }
    }
}
