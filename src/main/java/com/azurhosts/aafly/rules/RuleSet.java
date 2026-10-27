package com.azurhosts.aafly.rules;

import com.azurhosts.aafly.action.Action;
import com.azurhosts.aafly.action.ActionParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Ensemble immuable de règles triées par priorité croissante (tri stable : à priorité égale,
 * l'ordre du fichier est conservé). La première règle dont toutes les conditions correspondent
 * décide ; aucune règle → {@link RuleDecision#DEFAULT_ALLOW}.
 *
 * <p>Format d'une règle :
 * <pre>
 * nom:
 *   priority: 1                 # entier, défaut 0
 *   conditions:                 # table nom → condition, ou liste ; absente = toujours vraie
 *     c: { type: string_equals, input: "%x%", value: "true", negate: false }
 *   result: DENY                # ALLOW | DENY (obligatoire pour fly-rules, ignoré pour decrement-rules)
 *   actions: ["[actionbar] ..."] # fly-rules uniquement, exécutées sur DENY
 * </pre>
 * Une règle invalide est journalisée (WARN : section, nom, raison) puis ignorée ; jamais de crash.
 */
public final class RuleSet {

    /** Type de section. */
    public enum Kind {
        /** {@code fly-rules} : résultat ALLOW/DENY et actions. */
        FLY("fly-rules"),
        /** {@code decrement-rules} : une règle qui correspond suspend le décompte. */
        DECREMENT("decrement-rules");

        private final String section;

        Kind(String section) {
            this.section = section;
        }

        /**
         * @return nom de la section YAML
         */
        public String section() {
            return section;
        }
    }

    /** Ensemble vide. */
    public static final RuleSet EMPTY = new RuleSet(List.of());

    /** Nombre maximal de règles par section. */
    public static final int MAX_RULES = 512;
    /** Nombre maximal de conditions par règle. */
    public static final int MAX_CONDITIONS = 64;
    /** Format d'un nom de règle ou de condition. */
    public static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}$");

    private static final Set<String> KNOWN_KEYS = Set.of("priority", "conditions", "result", "actions");

    private final List<Rule> rules;
    private final boolean usesPlaceholders;

    private RuleSet(List<Rule> sorted) {
        this.rules = List.copyOf(sorted);
        boolean ph = false;
        for (Rule r : this.rules) {
            if (r.usesPlaceholders()) {
                ph = true;
                break;
            }
        }
        this.usesPlaceholders = ph;
    }

    /**
     * Construit un ensemble à partir de règles déjà construites (triées ici).
     *
     * @param rules règles
     * @return ensemble trié
     */
    public static RuleSet of(List<Rule> rules) {
        List<Rule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(Rule::priority));
        return new RuleSet(sorted);
    }

    /**
     * Charge une section de règles.
     *
     * @param section  nœud de la section (table nom → règle ; vide ou absent → ensemble vide)
     * @param kind     type de section
     * @param registry registre des conditions
     * @param parser   analyseur d'actions ({@code null} : actions ignorées)
     * @param logger   logger (WARN pour chaque règle ignorée)
     * @return ensemble des règles valides
     */
    public static RuleSet load(ConfigurationNode section, Kind kind, ConditionRegistry registry,
                               @Nullable ActionParser parser, Logger logger) {
        if (section == null || section.virtual() || section.raw() == null) {
            return EMPTY;
        }
        if (!section.isMap()) {
            if (!(section.isList() && section.childrenList().isEmpty())) {
                logger.warning("[" + kind.section() + "] doit être une table nom -> règle : section ignorée.");
            }
            return EMPTY;
        }
        List<Rule> rules = new ArrayList<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> e : section.childrenMap().entrySet()) {
            String name = String.valueOf(e.getKey());
            if (rules.size() >= MAX_RULES) {
                logger.warning("[" + kind.section() + "] plus de " + MAX_RULES + " règles : les suivantes sont ignorées.");
                break;
            }
            try {
                rules.add(parseRule(name, e.getValue(), kind, registry, parser, logger));
            } catch (InvalidRuleException ex) {
                logger.warning("[" + kind.section() + "] Règle '" + safe(name) + "' ignorée : " + ex.getMessage());
            }
        }
        return of(rules);
    }

    /**
     * Analyse une règle.
     *
     * @param name     nom
     * @param node     nœud de la règle
     * @param kind     type de section
     * @param registry registre
     * @param parser   analyseur d'actions (peut être {@code null})
     * @param logger   logger (avertissements non bloquants)
     * @return règle
     * @throws InvalidRuleException si la règle est invalide
     */
    static Rule parseRule(String name, ConfigurationNode node, Kind kind, ConditionRegistry registry,
                          @Nullable ActionParser parser, Logger logger) throws InvalidRuleException {
        if (!NAME.matcher(name).matches()) {
            throw new InvalidRuleException("nom invalide (attendu ^[A-Za-z0-9_-]{1,64}$)");
        }
        if (!node.isMap()) {
            throw new InvalidRuleException("la règle doit être une table");
        }
        for (Object key : node.childrenMap().keySet()) {
            String k = String.valueOf(key);
            if (!KNOWN_KEYS.contains(k)) {
                logger.warning("[" + kind.section() + "] Règle '" + name + "' : clé inconnue '" + safe(k) + "' ignorée.");
            }
        }
        int priority = parsePriority(node.node("priority"));
        RuleResult result = kind == Kind.FLY ? parseResult(node.node("result")) : RuleResult.DENY;
        List<Condition> conditions = parseConditions(node.node("conditions"), registry);

        List<Action> actions = List.of();
        ConfigurationNode actionsNode = node.node("actions");
        if (!actionsNode.virtual() && actionsNode.raw() != null) {
            List<String> raws = actionStrings(actionsNode);
            if (kind == Kind.DECREMENT) {
                if (!raws.isEmpty()) {
                    logger.warning("[" + kind.section() + "] Règle '" + name + "' : les actions ne sont pas prises en charge ici, ignorées.");
                }
            } else if (result == RuleResult.ALLOW) {
                if (!raws.isEmpty()) {
                    logger.warning("[" + kind.section() + "] Règle '" + name + "' : actions ignorées (exécutées uniquement sur DENY).");
                }
            } else if (parser != null) {
                actions = parser.parseAll(raws, kind.section() + "." + name + ".actions");
            }
        }
        return new Rule(name, priority, conditions, result, actions);
    }

    private static int parsePriority(ConfigurationNode node) throws InvalidRuleException {
        if (node.virtual() || node.raw() == null) {
            return 0;
        }
        Object raw = node.raw();
        long value;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            value = ((Number) raw).longValue();
        } else if (node.isMap() || node.isList()) {
            throw new InvalidRuleException("'priority' doit être un entier");
        } else {
            try {
                value = Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new InvalidRuleException("'priority' doit être un entier");
            }
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new InvalidRuleException("'priority' hors limites");
        }
        return (int) value;
    }

    private static RuleResult parseResult(ConfigurationNode node) throws InvalidRuleException {
        if (node.virtual() || node.raw() == null || node.isMap() || node.isList()) {
            throw new InvalidRuleException("'result' manquant (ALLOW ou DENY)");
        }
        String raw = String.valueOf(node.raw()).trim().toUpperCase(Locale.ROOT);
        return switch (raw) {
            case "ALLOW" -> RuleResult.ALLOW;
            case "DENY" -> RuleResult.DENY;
            default -> throw new InvalidRuleException("'result' invalide (ALLOW ou DENY attendu)");
        };
    }

    private static List<Condition> parseConditions(ConfigurationNode node, ConditionRegistry registry)
            throws InvalidRuleException {
        if (node.virtual() || node.raw() == null) {
            return List.of();
        }
        List<Condition> out = new ArrayList<>();
        if (node.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> e : node.childrenMap().entrySet()) {
                String cname = String.valueOf(e.getKey());
                if (!NAME.matcher(cname).matches()) {
                    throw new InvalidRuleException("nom de condition invalide '" + safe(cname) + "'");
                }
                out.add(parseCondition(cname, e.getValue(), registry));
                if (out.size() > MAX_CONDITIONS) {
                    throw new InvalidRuleException("trop de conditions (max " + MAX_CONDITIONS + ")");
                }
            }
        } else if (node.isList()) {
            int i = 0;
            for (ConfigurationNode child : node.childrenList()) {
                out.add(parseCondition("#" + i++, child, registry));
                if (out.size() > MAX_CONDITIONS) {
                    throw new InvalidRuleException("trop de conditions (max " + MAX_CONDITIONS + ")");
                }
            }
        } else {
            throw new InvalidRuleException("'conditions' doit être une table ou une liste");
        }
        return out;
    }

    private static Condition parseCondition(String cname, ConfigurationNode node, ConditionRegistry registry)
            throws InvalidRuleException {
        if (!node.isMap()) {
            throw new InvalidRuleException("condition '" + cname + "' : table attendue");
        }
        ConfigurationNode typeNode = node.node("type");
        if (typeNode.virtual() || typeNode.raw() == null || typeNode.isMap() || typeNode.isList()) {
            throw new InvalidRuleException("condition '" + cname + "' : 'type' manquant");
        }
        try {
            return registry.create(String.valueOf(typeNode.raw()), node);
        } catch (InvalidRuleException e) {
            throw new InvalidRuleException("condition '" + cname + "' : " + e.getMessage(), e);
        }
    }

    private static List<String> actionStrings(ConfigurationNode node) throws InvalidRuleException {
        if (node.isList()) {
            List<String> out = new ArrayList<>();
            for (ConfigurationNode child : node.childrenList()) {
                if (child.isMap() || child.isList() || child.raw() == null) {
                    throw new InvalidRuleException("'actions' doit être une liste de textes");
                }
                out.add(String.valueOf(child.raw()));
            }
            return out;
        }
        if (node.isMap()) {
            throw new InvalidRuleException("'actions' doit être une liste de textes");
        }
        return List.of(String.valueOf(node.raw()));
    }

    /**
     * @param context contexte
     * @return première règle qui correspond, ou {@code null}
     */
    public @Nullable Rule firstMatch(RuleContext context) {
        for (Rule rule : rules) {
            if (rule.matches(context)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * @param context contexte
     * @return décision (ALLOW par défaut)
     */
    public RuleDecision evaluate(RuleContext context) {
        Rule rule = firstMatch(context);
        return rule == null ? RuleDecision.DEFAULT_ALLOW : new RuleDecision(rule.result(), rule);
    }

    /**
     * @return règles triées (non modifiable)
     */
    public List<Rule> rules() {
        return rules;
    }

    /**
     * @return {@code true} si au moins une règle résout des placeholders
     */
    public boolean usesPlaceholders() {
        return usesPlaceholders;
    }

    /**
     * @return nombre de règles
     */
    public int size() {
        return rules.size();
    }

    /**
     * @return {@code true} si aucune règle
     */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    static String safe(String value) {
        if (value == null) {
            return "null";
        }
        String v = value.replaceAll("[\\p{Cntrl}]", " ");
        return v.length() > 64 ? v.substring(0, 64) + "…" : v;
    }
}
