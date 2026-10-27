package com.azurhosts.aafly.rules;

import com.azurhosts.aafly.rules.conditions.BuiltinConditions;
import com.azurhosts.aafly.rules.conditions.ConditionNodes;
import com.azurhosts.aafly.rules.conditions.NegatedCondition;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Registre thread-safe des types de conditions, pré-rempli avec les types intégrés.
 *
 * <p>Paramètre commun à tous les types : {@code negate: true} inverse la condition.
 * Une fabrique (y compris tierce, via l'API) qui lève une exception inattendue ou renvoie
 * {@code null} produit une {@link InvalidRuleException} : la règle est ignorée, jamais de crash.
 */
public final class DefaultConditionRegistry implements ConditionRegistry {

    /** Format d'un identifiant de type. */
    public static final Pattern TYPE = Pattern.compile("^[a-z0-9_]{1,64}$");

    private final ConcurrentMap<String, ConditionFactory> factories = new ConcurrentHashMap<>();

    /** Crée un registre contenant les types intégrés. */
    public DefaultConditionRegistry() {
        BuiltinConditions.registerAll(this);
    }

    @Override
    public void register(String type, ConditionFactory factory) {
        Objects.requireNonNull(factory, "factory");
        String key = normalize(type);
        if (key == null || !TYPE.matcher(key).matches()) {
            throw new IllegalArgumentException("Type de condition invalide (attendu ^[a-z0-9_]{1,64}$)");
        }
        if (factories.putIfAbsent(key, factory) != null) {
            throw new IllegalArgumentException("Type de condition déjà enregistré : " + key);
        }
    }

    @Override
    public Condition create(String type, ConfigurationNode node) throws InvalidRuleException {
        Objects.requireNonNull(node, "node");
        String key = normalize(type);
        if (key == null || key.isEmpty()) {
            throw new InvalidRuleException("type de condition manquant");
        }
        ConditionFactory factory = factories.get(key);
        if (factory == null) {
            String shown = key.length() > 64 ? key.substring(0, 64) + "…" : key;
            throw new InvalidRuleException("type de condition inconnu '" + shown + "' (types connus : "
                    + String.join(", ", types()) + ")");
        }
        boolean negate = ConditionNodes.optionalBoolean(node, "negate", false);
        Condition condition;
        try {
            condition = factory.create(node);
        } catch (InvalidRuleException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidRuleException("erreur inattendue de la fabrique '" + key + "' : "
                    + e.getClass().getSimpleName(), e);
        }
        if (condition == null) {
            throw new InvalidRuleException("la fabrique '" + key + "' n'a produit aucune condition");
        }
        return negate ? new NegatedCondition(condition) : condition;
    }

    @Override
    public boolean has(String type) {
        String key = normalize(type);
        return key != null && factories.containsKey(key);
    }

    @Override
    public Set<String> types() {
        return Set.copyOf(new TreeSet<>(factories.keySet()));
    }

    private static String normalize(String type) {
        return type == null ? null : type.trim().toLowerCase(Locale.ROOT);
    }
}
