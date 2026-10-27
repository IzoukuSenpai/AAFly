package com.azurhosts.aafly.rules;

import com.azurhosts.aafly.action.ActionParser;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.DefaultFlightService;
import com.azurhosts.aafly.core.FlightService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Implémentation du {@link RuleEngine} (SPEC §5) :
 * <ul>
 *   <li>règles triées par priorité croissante, première correspondance gagnante, défaut ALLOW ;</li>
 *   <li>contournement par {@code aafly.bypass.rules} (et {@code wfly.bypass.fly} si
 *       {@code compat.wfly-permissions}) : ALLOW, et pas de décompte si {@code rules.bypass-no-decrement} ;</li>
 *   <li>décisions {@code fly-rules} mises en cache {@code rules.check-interval-seconds} par joueur.</li>
 * </ul>
 * Main thread uniquement. {@link #reload(Settings)} doit être appelé une première fois avant usage
 * (sinon aucune règle : ALLOW partout).
 */
public final class DefaultRuleEngine implements RuleEngine {

    private record State(RuleSet fly, RuleSet decrement, boolean bypassNoDecrement, boolean wflyPermissions) {
    }

    private final ConditionRegistry registry;
    private final @Nullable ActionParser actionParser;
    private final Logger logger;
    private final DecisionCache cache;
    private volatile State state = new State(RuleSet.EMPTY, RuleSet.EMPTY, true, false);

    /**
     * @param registry     registre des conditions (partagé avec l'API)
     * @param actionParser analyseur d'actions des règles DENY ({@code null} : actions ignorées)
     * @param logger       logger du plugin
     * @param clockMillis  horloge (millisecondes) pour le cache
     */
    public DefaultRuleEngine(ConditionRegistry registry, @Nullable ActionParser actionParser, Logger logger,
                             LongSupplier clockMillis) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.actionParser = actionParser;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.cache = new DecisionCache(Objects.requireNonNull(clockMillis, "clockMillis"), 1_000L);
    }

    /**
     * @param registry     registre des conditions
     * @param actionParser analyseur d'actions
     * @param logger       logger
     */
    public DefaultRuleEngine(ConditionRegistry registry, @Nullable ActionParser actionParser, Logger logger) {
        this(registry, actionParser, logger, System::currentTimeMillis);
    }

    @Override
    public RuleDecision evaluateFly(RuleContext context) {
        return evaluateFly(context, bypasses(context.player()));
    }

    @Override
    public RuleDecision evaluateFly(RuleContext context, boolean bypass) {
        if (bypass) {
            return RuleDecision.DEFAULT_ALLOW;
        }
        UUID uuid = context.player().getUniqueId();
        RuleDecision cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        RuleDecision decision = state.fly().evaluate(context);
        cache.put(uuid, decision);
        return decision;
    }

    /**
     * Évalue {@code fly-rules} sans cache ni contournement (diagnostic, tests).
     *
     * @param context contexte
     * @return décision
     */
    public RuleDecision evaluateFlyUncached(RuleContext context) {
        return state.fly().evaluate(context);
    }

    @Override
    public boolean shouldSkipDecrement(RuleContext context) {
        return shouldSkipDecrement(context, bypasses(context.player()));
    }

    @Override
    public boolean shouldSkipDecrement(RuleContext context, boolean bypass) {
        State s = state;
        if (bypass) {
            return s.bypassNoDecrement();
        }
        return s.decrement().firstMatch(context) != null;
    }

    /**
     * Même logique que {@code DefaultFlightService#bypassesRules} : un OP n'hérite pas implicitement du
     * contournement, la permission doit lui être explicitement accordée.
     *
     * @param player joueur
     * @return {@code true} s'il contourne les règles
     */
    public boolean bypasses(Player player) {
        if (DefaultFlightService.granted(player, FlightService.PERM_BYPASS_RULES)) {
            return true;
        }
        return state.wflyPermissions() && DefaultFlightService.granted(player, FlightService.PERM_WFLY_BYPASS);
    }

    @Override
    public void reload(Settings settings) {
        Objects.requireNonNull(settings, "settings");
        RuleSet fly = RuleSet.load(settings.flyRules(), RuleSet.Kind.FLY, registry, actionParser, logger);
        RuleSet decrement = RuleSet.load(settings.decrementRules(), RuleSet.Kind.DECREMENT, registry, null, logger);
        long ttl = Math.max(0L, settings.rules().checkIntervalSeconds()) * 1_000L;
        this.state = new State(fly, decrement, settings.rules().bypassNoDecrement(), settings.compat().wflyPermissions());
        cache.ttl(ttl);
        logger.info("Règles chargées : " + fly.size() + " fly-rules, " + decrement.size() + " decrement-rules"
                + (fly.usesPlaceholders() || decrement.usesPlaceholders() ? " (placeholders utilisés)." : "."));
    }

    @Override
    public void invalidate(UUID uuid) {
        cache.invalidate(uuid);
    }

    /** Vide toutes les décisions en cache. */
    public void clearCache() {
        cache.clear();
    }

    @Override
    public boolean usesPlaceholders() {
        State s = state;
        return s.fly().usesPlaceholders() || s.decrement().usesPlaceholders();
    }

    @Override
    public List<Rule> flyRules() {
        return state.fly().rules();
    }

    @Override
    public List<Rule> decrementRules() {
        return state.decrement().rules();
    }

    /**
     * @return registre des conditions
     */
    public ConditionRegistry registry() {
        return registry;
    }
}
