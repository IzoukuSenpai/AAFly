package com.azurhosts.aafly.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.action.ConfiguredAction;
import com.azurhosts.aafly.action.DefaultActionParser;
import com.azurhosts.aafly.action.NoopActionRuntime;
import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.rules.conditions.NumberCondition;
import com.azurhosts.aafly.rules.conditions.RegexCondition;
import com.azurhosts.aafly.rules.conditions.StringCondition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Tests du moteur de règles. Les conditions utilisées n'accèdent pas au contexte
 * (aucun placeholder, aucune permission) : le contexte peut donc être {@code null}.
 */
class RuleEngineTest {

    private static final Condition TRUE = ctx -> true;
    private static final Condition FALSE = ctx -> false;

    private Logger logger;
    private final List<String> warnings = new ArrayList<>();
    private Handler handler;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger("AAFly-test-rules-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(handler);
    }

    private static Rule rule(String name, int priority, RuleResult result, Condition... conditions) {
        return new Rule(name, priority, List.of(conditions), result, List.of());
    }

    private static ConfigurationNode yaml(String text) throws ConfigurateException {
        return YamlConfigurationLoader.builder().buildAndLoadString(text);
    }

    private static Settings settings(ConfigurationNode root) {
        return new Settings(Settings.CURRENT_VERSION, false, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, root.node("fly-rules"), root.node("decrement-rules"));
    }

    private DefaultRuleEngine engine() {
        return new DefaultRuleEngine(new DefaultConditionRegistry(),
                new DefaultActionParser(new NoopActionRuntime(), logger, false), logger);
    }

    // ------------------------------------------------------------------ priorité / défaut

    @Test
    void priorityCroissanteLaPremiereQuiCorrespondGagne() {
        Rule deny = rule("deny", 5, RuleResult.DENY, TRUE);
        Rule allow = rule("allow", 1, RuleResult.ALLOW, TRUE);
        Rule last = rule("last", 999, RuleResult.DENY, TRUE);
        RuleSet set = RuleSet.of(List.of(deny, last, allow));
        assertEquals(List.of("allow", "deny", "last"), set.rules().stream().map(Rule::name).toList());
        RuleDecision d = set.evaluate(null);
        assertEquals(RuleResult.ALLOW, d.result());
        assertSame(allow, d.matched());
    }

    @Test
    void priorityEgaleConserveLOrdreDuFichier() {
        Rule a = rule("a", 1, RuleResult.DENY, TRUE);
        Rule b = rule("b", 1, RuleResult.ALLOW, TRUE);
        assertSame(a, RuleSet.of(List.of(a, b)).evaluate(null).matched());
        assertSame(b, RuleSet.of(List.of(b, a)).evaluate(null).matched());
    }

    @Test
    void reglesQuiNeCorrespondentPasSontSautees() {
        Rule skip = rule("skip", 0, RuleResult.ALLOW, FALSE);
        Rule deny = rule("deny", 1, RuleResult.DENY, TRUE);
        RuleDecision d = RuleSet.of(List.of(skip, deny)).evaluate(null);
        assertFalse(d.allowed());
        assertSame(deny, d.matched());
    }

    @Test
    void toutesLesConditionsDoiventCorrespondre() {
        assertFalse(rule("r", 0, RuleResult.DENY, TRUE, FALSE).matches(null));
        assertFalse(rule("r", 0, RuleResult.DENY, FALSE, TRUE).matches(null));
        assertTrue(rule("r", 0, RuleResult.DENY, TRUE, TRUE, TRUE).matches(null));
        assertTrue(rule("vide", 0, RuleResult.DENY).matches(null), "sans condition = toujours vraie");
    }

    @Test
    void defautAllow() {
        assertSame(RuleDecision.DEFAULT_ALLOW, RuleSet.EMPTY.evaluate(null));
        RuleDecision d = RuleSet.of(List.of(rule("x", 0, RuleResult.DENY, FALSE))).evaluate(null);
        assertSame(RuleDecision.DEFAULT_ALLOW, d);
        assertTrue(d.allowed());
        assertNull(d.matched());
    }

    // ------------------------------------------------------------------ chargement YAML

    @Test
    void chargementDepuisLaConfiguration() throws ConfigurateException {
        ConfigurationNode root = yaml("""
                fly-rules:
                  default:
                    priority: 999
                    result: ALLOW
                  no_combat:
                    priority: 1
                    conditions:
                      c: { type: string_equals, input: "%combatlogx_in_combat%", value: "true" }
                    result: DENY
                    actions: ["[actionbar] <red>Combat en cours", "[player] spawn"]
                  multi:
                    priority: 0
                    conditions:
                      a: { type: string_equals, input: "oui", value: "oui" }
                      b: { type: number_greater, input: "12", value: 10 }
                    result: deny
                decrement-rules:
                  zone:
                    conditions:
                      - { type: string_contains_ignorecase, input: "SpawnZone", value: "spawn" }
                """);
        DefaultRuleEngine engine = engine();
        engine.reload(settings(root));

        assertEquals(List.of("multi", "no_combat", "default"), engine.flyRules().stream().map(Rule::name).toList());
        assertTrue(engine.usesPlaceholders());
        Rule noCombat = engine.flyRules().get(1);
        assertTrue(noCombat.usesPlaceholders());
        // [player] refusé (allow-player-commands: false) → une seule action conservée.
        assertEquals(1, noCombat.actions().size());
        assertInstanceOf(ConfiguredAction.class, noCombat.actions().get(0));

        // "multi" : deux conditions vraies, priorité 0 → DENY.
        RuleDecision d = engine.evaluateFlyUncached(null);
        assertEquals(RuleResult.DENY, d.result());
        assertEquals("multi", d.matched().name());

        assertEquals(1, engine.decrementRules().size());
        assertEquals(RuleResult.DENY, engine.decrementRules().get(0).result());
        assertTrue(engine.decrementRules().get(0).matches(null));
    }

    @Test
    void regleInvalideIgnoreeAvecWarnSansCrash() throws ConfigurateException {
        ConfigurationNode root = yaml("""
                fly-rules:
                  type_inconnu:
                    conditions:
                      c: { type: pas_un_type, input: "a", value: "b" }
                    result: DENY
                  sans_result:
                    conditions:
                      c: { type: string_equals, input: "a", value: "a" }
                  regex_cassee:
                    conditions:
                      c: { type: string_regex, input: "a", pattern: "([a-z" }
                    result: DENY
                  nombre_invalide:
                    conditions:
                      c: { type: number_less, input: "1", value: "abc" }
                    result: DENY
                  between_inverse:
                    conditions:
                      c: { type: number_between, input: "1", min: 5, max: 1 }
                    result: DENY
                  sans_type:
                    conditions:
                      c: { input: "a" }
                    result: DENY
                  result_faux:
                    result: PEUT_ETRE
                  ok:
                    priority: 3
                    result: ALLOW
                """);
        DefaultRuleEngine engine = engine();
        engine.reload(settings(root));
        assertEquals(List.of("ok"), engine.flyRules().stream().map(Rule::name).toList());
        long ignored = warnings.stream().filter(w -> w.contains("ignorée")).count();
        assertEquals(7, ignored, warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("type_inconnu") && w.contains("pas_un_type")));
    }

    @Test
    void sectionAbsenteOuVide() throws ConfigurateException {
        DefaultRuleEngine engine = engine();
        engine.reload(settings(yaml("autre: 1")));
        assertTrue(engine.flyRules().isEmpty());
        assertTrue(engine.decrementRules().isEmpty());
        assertFalse(engine.usesPlaceholders());
        assertSame(RuleDecision.DEFAULT_ALLOW, engine.evaluateFlyUncached(null));
    }

    @Test
    void negateEtActionsIgnoreesSurDecrement() throws ConfigurateException {
        ConfigurationNode root = yaml("""
                decrement-rules:
                  pas_au_spawn:
                    conditions:
                      c: { type: string_equals, input: "a", value: "b", negate: true }
                    actions: ["[message] x"]
                """);
        DefaultRuleEngine engine = engine();
        engine.reload(settings(root));
        assertEquals(1, engine.decrementRules().size());
        assertTrue(engine.decrementRules().get(0).matches(null));
        assertTrue(engine.decrementRules().get(0).actions().isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("actions")));
    }

    // ------------------------------------------------------------------ registre & conditions

    @Test
    void registre() {
        DefaultConditionRegistry r = new DefaultConditionRegistry();
        for (String t : List.of("string_equals", "string_equals_ignorecase", "string_contains",
                "string_contains_ignorecase", "string_regex", "number_equals", "number_greater", "number_less",
                "number_between", "has_permission", "world", "gamemode")) {
            assertTrue(r.has(t), t);
        }
        assertTrue(r.has("STRING_EQUALS"));
        assertThrows(IllegalArgumentException.class, () -> r.register("string_equals", n -> TRUE));
        assertThrows(IllegalArgumentException.class, () -> r.register("Mauvais Type!", n -> TRUE));
        r.register("Custom_Type", n -> TRUE);
        assertTrue(r.has("custom_type"));
    }

    @Test
    void fabriqueTierceEnErreurNeCrashePas() throws ConfigurateException {
        DefaultConditionRegistry r = new DefaultConditionRegistry();
        r.register("boom", n -> {
            throw new IllegalStateException("x");
        });
        r.register("nul", n -> null);
        ConfigurationNode node = yaml("type: boom");
        assertThrows(InvalidRuleException.class, () -> r.create("boom", node));
        assertThrows(InvalidRuleException.class, () -> r.create("nul", node));
        assertThrows(InvalidRuleException.class, () -> r.create("inconnu", node));
    }

    @Test
    void conditionsTexte() {
        assertTrue(StringCondition.compare(StringCondition.Operator.EQUALS, "true", "true"));
        assertFalse(StringCondition.compare(StringCondition.Operator.EQUALS, "TRUE", "true"));
        assertTrue(StringCondition.compare(StringCondition.Operator.EQUALS_IGNORECASE, "TRUE", "true"));
        assertTrue(StringCondition.compare(StringCondition.Operator.CONTAINS, "region_spawn", "spawn"));
        assertFalse(StringCondition.compare(StringCondition.Operator.CONTAINS, "region_SPAWN", "spawn"));
        assertTrue(StringCondition.compare(StringCondition.Operator.CONTAINS_IGNORECASE, "region_SPAWN", "spawn"));
        // Entrée tronquée à 256 caractères.
        String longue = "a".repeat(300) + "fin";
        assertFalse(StringCondition.compare(StringCondition.Operator.CONTAINS, longue, "fin"));
    }

    @Test
    void conditionsNumeriques() {
        NumberCondition gt = new NumberCondition(NumberCondition.Operator.GREATER, "%x%", 10, 0);
        assertTrue(gt.matches("10.5"));
        assertFalse(gt.matches("10"));
        assertFalse(gt.matches("abc"));
        assertFalse(gt.matches("NaN"));
        assertFalse(gt.matches("Infinity"));
        assertTrue(gt.matches("1,234.5"));
        assertTrue(gt.matches("12,5"));
        NumberCondition lt = new NumberCondition(NumberCondition.Operator.LESS, "%x%", 0, 0);
        assertTrue(lt.matches("-3"));
        NumberCondition eq = new NumberCondition(NumberCondition.Operator.EQUALS, "%x%", 3, 0);
        assertTrue(eq.matches("3.0"));
        NumberCondition between = new NumberCondition(NumberCondition.Operator.BETWEEN, "%x%", 1, 5);
        assertTrue(between.matches("1"));
        assertTrue(between.matches("5"));
        assertFalse(between.matches("5.01"));
        assertTrue(gt.usesPlaceholders());
    }

    @Test
    void conditionRegex() throws InvalidRuleException {
        RegexCondition full = new RegexCondition("%r%", RegexCondition.compile("spawn_\\d+"), true);
        assertTrue(full.matches("spawn_12"));
        assertFalse(full.matches("le spawn_12"));
        RegexCondition partial = new RegexCondition("%r%", RegexCondition.compile("spawn_\\d+"), false);
        assertTrue(partial.matches("le spawn_12"));
        assertThrows(InvalidRuleException.class, () -> RegexCondition.compile("(("));
        assertThrows(InvalidRuleException.class, () -> RegexCondition.compile("a".repeat(300)));
    }

    // ------------------------------------------------------------------ cache & PVP

    @Test
    void cacheDeDecisionExpire() {
        AtomicLong now = new AtomicLong(1_000L);
        DecisionCache cache = new DecisionCache(now::get, 1_000L);
        UUID u = UUID.randomUUID();
        RuleDecision deny = new RuleDecision(RuleResult.DENY, null);
        cache.put(u, deny);
        now.addAndGet(999L);
        assertSame(deny, cache.get(u));
        now.addAndGet(1L);
        assertNull(cache.get(u));
        cache.put(u, deny);
        cache.invalidate(u);
        assertNull(cache.get(u));
        cache.ttl(0L);
        cache.put(u, deny);
        assertNull(cache.get(u), "ttl 0 : cache désactivé");
    }

    @Test
    void alliancePvp() {
        List<String> keys = List.of("%lands_land_name%", "%factions_faction%");
        assertTrue(NearbyPlayerChecker.allied(keys, List.of("Azur", "F1"), List.of("Azur", "F1")));
        assertFalse(NearbyPlayerChecker.allied(keys, List.of("Azur", "F1"), List.of("Azur", "F2")));
        assertFalse(NearbyPlayerChecker.allied(keys, List.of("", ""), List.of("", "")), "sans faction = ennemis");
        assertFalse(NearbyPlayerChecker.allied(keys, keys, keys), "placeholders non résolus = ennemis");
        assertFalse(NearbyPlayerChecker.allied(List.of(), List.of(), List.of()));
    }
}
