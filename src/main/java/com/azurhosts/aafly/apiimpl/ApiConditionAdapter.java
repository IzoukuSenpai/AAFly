package com.azurhosts.aafly.apiimpl;

import com.azurhosts.aafly.api.ConditionContext;
import com.azurhosts.aafly.api.ConditionDefinition;
import com.azurhosts.aafly.api.FlightCondition;
import com.azurhosts.aafly.rules.Condition;
import com.azurhosts.aafly.rules.ConditionFactory;
import com.azurhosts.aafly.rules.InvalidRuleException;
import com.azurhosts.aafly.rules.RuleContext;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Adapte une {@link com.azurhosts.aafly.api.ConditionFactory} publique (sans Configurate) au registre interne
 * {@link com.azurhosts.aafly.rules.ConditionRegistry}.
 *
 * <p>Isolation du code tiers : une {@link IllegalArgumentException} de la fabrique devient une
 * {@link InvalidRuleException} (règle ignorée avec WARN) ; une exception pendant l'évaluation vaut
 * {@code false} et n'est journalisée qu'une fois par condition (pas de flood à chaque seconde).
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class ApiConditionAdapter implements ConditionFactory {

    /** Longueur maximale d'un message d'erreur tiers recopié dans les logs. */
    private static final int MAX_MESSAGE = 200;

    private final String type;
    private final com.azurhosts.aafly.api.ConditionFactory factory;
    private final Logger logger;

    /**
     * @param type    type enregistré
     * @param factory fabrique publique
     * @param logger  logger du plugin
     */
    public ApiConditionAdapter(String type, com.azurhosts.aafly.api.ConditionFactory factory, Logger logger) {
        this.type = Objects.requireNonNull(type, "type").trim().toLowerCase(Locale.ROOT);
        this.factory = Objects.requireNonNull(factory, "factory");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public Condition create(ConfigurationNode node) throws InvalidRuleException {
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> e : node.childrenMap().entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!key.equals("type") && !key.equals("negate")) {
                params.put(key, e.getValue().raw());
            }
        }
        FlightCondition condition;
        try {
            condition = factory.create(new ConditionDefinition(type, params));
        } catch (IllegalArgumentException e) {
            throw new InvalidRuleException("condition '" + type + "' invalide : " + shorten(e.getMessage()));
        } catch (RuntimeException | LinkageError e) {
            // Fabrique tierce défaillante : règle ignorée avec WARN, jamais de crash du chargement.
            throw new InvalidRuleException("fabrique '" + type + "' en erreur : " + e.getClass().getSimpleName()
                    + " " + shorten(e.getMessage()));
        }
        if (condition == null) {
            throw new InvalidRuleException("la fabrique '" + type + "' n'a produit aucune condition");
        }
        return new Adapted(condition, type, logger, new AtomicBoolean());
    }

    private static String shorten(String message) {
        if (message == null) {
            return "(sans message)";
        }
        String oneLine = message.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > MAX_MESSAGE ? oneLine.substring(0, MAX_MESSAGE) + "…" : oneLine;
    }

    /** Condition interne déléguant à la condition publique. */
    private record Adapted(FlightCondition delegate, String type, Logger logger, AtomicBoolean warned)
            implements Condition {

        @Override
        public boolean test(RuleContext context) {
            try {
                return delegate.test(new View(context));
            } catch (RuntimeException | LinkageError e) {
                // LinkageError : plugin tiers déchargé (classes indisponibles) ; ne remonte jamais au ticker.
                if (warned.compareAndSet(false, true)) {
                    logger.log(Level.WARNING, "Condition personnalisée '" + type
                            + "' en erreur : évaluée à false (avertissement unique)", e);
                }
                return false;
            }
        }

        @Override
        public boolean usesPlaceholders() {
            try {
                return delegate.usesPlaceholders();
            } catch (RuntimeException | LinkageError e) {
                return true;
            }
        }
    }

    /** Vue publique d'un {@link RuleContext}. */
    private record View(RuleContext context) implements ConditionContext {

        @Override
        public Player player() {
            return context.player();
        }

        @Override
        public String resolve(String input) {
            return context.resolve(input);
        }
    }
}
