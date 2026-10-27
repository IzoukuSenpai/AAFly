package com.azurhosts.aafly.rules;

import com.azurhosts.aafly.rules.conditions.BuiltinConditions;
import com.azurhosts.aafly.rules.conditions.CompositeCondition;
import com.azurhosts.aafly.rules.conditions.ConditionNodes;
import com.azurhosts.aafly.rules.conditions.NegatedCondition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Registre thread-safe des types de conditions, pré-rempli avec les types intégrés.
 *
 * <p>Grammaire d'une condition (SPEC-phase2 A1) :
 * <ul>
 *   <li>type insensible à la casse ({@code STRING_EQUALS} = {@code string_equals}) ;</li>
 *   <li>négation : préfixe {@code !} sur le type ({@code "!has_permission"}) <b>ou</b> {@code negate: true}
 *       (les deux à la fois : refusé, ambigu) ;</li>
 *   <li>composites réservés {@code any}/{@code or} et {@code all}/{@code and} avec {@code children:}
 *       (table nom → condition ou liste, même grammaire, récursif) ; profondeur max
 *       {@value CompositeCondition#MAX_DEPTH}, {@value CompositeCondition#MAX_CHILDREN} enfants max ;
 *       composite vide = erreur de chargement (jamais de {@code true} silencieux).</li>
 * </ul>
 * Une fabrique (y compris tierce, via l'API) qui lève une exception inattendue ou renvoie
 * {@code null} produit une {@link InvalidRuleException} : la règle est ignorée, jamais de crash.
 */
public final class DefaultConditionRegistry implements ConditionRegistry {

    /** Format d'un identifiant de type. */
    public static final Pattern TYPE = Pattern.compile("^[a-z0-9_]{1,64}$");
    /** Types composites réservés (non enregistrables). */
    public static final Set<String> COMPOSITE_TYPES = Set.of("any", "or", "all", "and");
    /** Clé des enfants d'une composite. */
    public static final String CHILDREN = "children";

    private final ConcurrentMap<String, ConditionFactory> factories = new ConcurrentHashMap<>();
    private final Set<String> builtins;

    /** Crée un registre contenant les types intégrés. */
    public DefaultConditionRegistry() {
        BuiltinConditions.registerAll(this);
        this.builtins = Set.copyOf(factories.keySet());
    }

    @Override
    public void register(String type, ConditionFactory factory) {
        Objects.requireNonNull(factory, "factory");
        String key = normalize(type);
        if (key == null || !TYPE.matcher(key).matches()) {
            throw new IllegalArgumentException("Type de condition invalide (attendu ^[a-z0-9_]{1,64}$)");
        }
        if (COMPOSITE_TYPES.contains(key)) {
            throw new IllegalArgumentException("Type de condition réservé : " + key);
        }
        if (factories.putIfAbsent(key, factory) != null) {
            throw new IllegalArgumentException("Type de condition déjà enregistré : " + key);
        }
    }

    @Override
    public boolean unregister(String type) {
        String key = normalize(type);
        if (key == null || builtins.contains(key) || COMPOSITE_TYPES.contains(key)) {
            return false;
        }
        return factories.remove(key) != null;
    }

    @Override
    public Condition create(String type, ConfigurationNode node) throws InvalidRuleException {
        return create(type, node, 1);
    }

    /**
     * Construit une condition à une profondeur donnée (récursion des composites).
     *
     * @param type  type brut (préfixe {@code !} accepté, insensible à la casse)
     * @param node  nœud de la condition
     * @param depth profondeur de ce nœud (1 = condition de premier niveau d'une règle)
     * @return condition
     * @throws InvalidRuleException si la condition est invalide ou dépasse les limites
     */
    public Condition create(String type, ConfigurationNode node, int depth) throws InvalidRuleException {
        Objects.requireNonNull(node, "node");
        if (depth > CompositeCondition.MAX_DEPTH) {
            throw new InvalidRuleException("profondeur d'imbrication dépassée (max " + CompositeCondition.MAX_DEPTH + ")");
        }
        String key = normalize(type);
        if (key == null || key.isEmpty()) {
            throw new InvalidRuleException("type de condition manquant");
        }
        boolean bang = false;
        if (key.startsWith("!")) {
            bang = true;
            key = key.substring(1).trim();
            if (key.isEmpty() || key.startsWith("!")) {
                throw new InvalidRuleException("type de condition invalide (un seul '!' autorisé)");
            }
        }
        boolean negateFlag = ConditionNodes.optionalBoolean(node, "negate", false);
        if (bang && negateFlag) {
            throw new InvalidRuleException("négation en double ('!' et negate: true) : choisir l'une des deux formes");
        }
        Condition condition = CompositeCondition.modeOf(key) != null
                ? composite(key, node, depth)
                : leaf(key, node);
        return bang || negateFlag ? new NegatedCondition(condition) : condition;
    }

    private Condition leaf(String key, ConfigurationNode node) throws InvalidRuleException {
        ConditionFactory factory = factories.get(key);
        if (factory == null) {
            throw new InvalidRuleException("type de condition inconnu '" + RuleSet.safe(key) + "' (types connus : "
                    + String.join(", ", types()) + ")");
        }
        Condition condition;
        try {
            condition = factory.create(node);
        } catch (InvalidRuleException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            throw new InvalidRuleException("erreur inattendue de la fabrique '" + key + "' : "
                    + e.getClass().getSimpleName(), e);
        }
        if (condition == null) {
            throw new InvalidRuleException("la fabrique '" + key + "' n'a produit aucune condition");
        }
        return condition;
    }

    private Condition composite(String key, ConfigurationNode node, int depth) throws InvalidRuleException {
        CompositeCondition.Mode mode = Objects.requireNonNull(CompositeCondition.modeOf(key));
        ConfigurationNode childrenNode = node.node(CHILDREN);
        if (childrenNode.virtual() || childrenNode.raw() == null) {
            throw new InvalidRuleException("'" + key + "' sans '" + CHILDREN + "' : composite vide refusée");
        }
        List<Condition> children = new ArrayList<>();
        if (childrenNode.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> e : childrenNode.childrenMap().entrySet()) {
                String name = String.valueOf(e.getKey());
                if (!RuleSet.NAME.matcher(name).matches()) {
                    throw new InvalidRuleException("'" + key + "' : nom d'enfant invalide '" + RuleSet.safe(name) + "'");
                }
                addChild(children, key, name, e.getValue(), depth);
            }
        } else if (childrenNode.isList()) {
            int i = 0;
            for (ConfigurationNode child : childrenNode.childrenList()) {
                addChild(children, key, "#" + i++, child, depth);
            }
        } else {
            throw new InvalidRuleException("'" + key + "." + CHILDREN + "' doit être une table ou une liste");
        }
        if (children.isEmpty()) {
            throw new InvalidRuleException("'" + key + "' vide : au moins une condition enfant est requise");
        }
        return new CompositeCondition(mode, children);
    }

    private void addChild(List<Condition> out, String parent, String name, ConfigurationNode child, int depth)
            throws InvalidRuleException {
        if (out.size() >= CompositeCondition.MAX_CHILDREN) {
            throw new InvalidRuleException("'" + parent + "' : trop d'enfants (max " + CompositeCondition.MAX_CHILDREN + ")");
        }
        if (!child.isMap()) {
            throw new InvalidRuleException("'" + parent + "' > '" + name + "' : table attendue");
        }
        ConfigurationNode typeNode = child.node("type");
        if (typeNode.virtual() || typeNode.raw() == null || typeNode.isMap() || typeNode.isList()) {
            throw new InvalidRuleException("'" + parent + "' > '" + name + "' : 'type' manquant");
        }
        try {
            out.add(create(String.valueOf(typeNode.raw()), child, depth + 1));
        } catch (InvalidRuleException e) {
            throw new InvalidRuleException("'" + parent + "' > '" + name + "' : " + e.getMessage(), e);
        }
    }

    @Override
    public boolean has(String type) {
        String key = normalize(type);
        if (key != null && key.startsWith("!")) {
            key = key.substring(1).trim();
        }
        return key != null && (factories.containsKey(key) || COMPOSITE_TYPES.contains(key));
    }

    @Override
    public Set<String> types() {
        TreeSet<String> all = new TreeSet<>(factories.keySet());
        all.addAll(COMPOSITE_TYPES);
        return Collections.unmodifiableSortedSet(all);
    }

    private static @Nullable String normalize(@Nullable String type) {
        return type == null ? null : type.trim().toLowerCase(Locale.ROOT);
    }
}
