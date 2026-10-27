package com.azurhosts.aafly.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.api.FlightCondition;
import com.azurhosts.aafly.rules.conditions.CompositeCondition;
import com.azurhosts.aafly.rules.conditions.NegatedCondition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * SPEC-phase2 A1/A2 : composites any/all, négation, casse, limites, tri stable, conditions tierces.
 * Les conditions utilisées ({@code string_equals} sans placeholder) n'accèdent pas au contexte : il peut être nul.
 */
class CompositeConditionTest {

    /** Condition vraie sans contexte. */
    private static final String T = "{ type: string_equals, input: a, value: a }";
    /** Condition fausse sans contexte. */
    private static final String F = "{ type: string_equals, input: a, value: b }";

    private Logger logger;
    private final List<String> warnings = new ArrayList<>();
    private DefaultConditionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultConditionRegistry();
        logger = Logger.getLogger("AAFly-test-composite-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
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
        });
    }

    private static ConfigurationNode yaml(String text) throws ConfigurateException {
        return YamlConfigurationLoader.builder().buildAndLoadString(text);
    }

    private Condition condition(String yamlMap) throws Exception {
        ConfigurationNode node = yaml("c: " + yamlMap).node("c");
        return registry.create(String.valueOf(node.node("type").raw()), node);
    }

    private boolean check(String yamlMap) throws Exception {
        return condition(yamlMap).test(null);
    }

    private RuleSet load(String yamlRules) throws ConfigurateException {
        return RuleSet.load(yaml(yamlRules).node("fly-rules"), RuleSet.Kind.FLY, registry, null, logger);
    }

    // ------------------------------------------------------------------ AND / OR / NOT

    @Test
    void andImpliciteDesConditionsDUneRegle() throws Exception {
        RuleSet set = load("""
                fly-rules:
                  r:
                    result: DENY
                    conditions:
                      a: %s
                      b: %s
                """.formatted(T, F));
        assertEquals(1, set.size());
        assertTrue(set.evaluate(null).allowed(), "T ET F = faux : défaut ALLOW");
    }

    @Test
    void allEtAliasAnd() throws Exception {
        assertTrue(check("{ type: all, children: { x: %s, y: %s } }".formatted(T, T)));
        assertFalse(check("{ type: all, children: { x: %s, y: %s } }".formatted(T, F)));
        assertFalse(check("{ type: and, children: [ %s, %s ] }".formatted(F, T)));
    }

    @Test
    void anyEtAliasOr() throws Exception {
        assertTrue(check("{ type: any, children: { x: %s, y: %s } }".formatted(F, T)));
        assertFalse(check("{ type: any, children: { x: %s, y: %s } }".formatted(F, F)));
        assertTrue(check("{ type: or, children: [ %s, %s ] }".formatted(T, F)));
        assertInstanceOf(CompositeCondition.class, condition("{ type: OR, children: [ %s ] }".formatted(T)));
    }

    @Test
    void negationParPrefixeEtParNegate() throws Exception {
        assertFalse(check("{ type: \"!string_equals\", input: a, value: a }"));
        assertTrue(check("{ type: \"!string_equals\", input: a, value: b }"));
        assertFalse(check("{ type: string_equals, input: a, value: a, negate: true }"));
        assertTrue(check("{ type: \"!any\", children: [ %s, %s ] }".formatted(F, F)));
        assertFalse(check("{ type: all, negate: true, children: [ %s ] }".formatted(T)));
        assertInstanceOf(NegatedCondition.class, condition("{ type: \"!STRING_EQUALS\", input: a, value: a }"));
    }

    @Test
    void doubleNegationRefusee() {
        assertThrows(InvalidRuleException.class,
                () -> condition("{ type: \"!string_equals\", input: a, value: a, negate: true }"));
        assertThrows(InvalidRuleException.class, () -> condition("{ type: \"!!string_equals\", input: a, value: a }"));
        assertThrows(InvalidRuleException.class, () -> condition("{ type: \"!\" }"));
    }

    @Test
    void typesInsensiblesALaCasse() throws Exception {
        assertTrue(check("{ type: STRING_EQUALS, input: a, value: a }"));
        assertTrue(check("{ type: String_Equals_IgnoreCase, input: A, value: a }"));
        assertTrue(registry.has("ANY"));
        assertTrue(registry.has("!has_permission"));
    }

    // ------------------------------------------------------------------ imbrication / limites

    @Test
    void imbrication() throws Exception {
        // (F OU (T ET NON F)) ET T
        String nested = """
                { type: all, children: {
                    left: { type: any, children: {
                      f: %s,
                      inner: { type: and, children: { t: %s, nf: { type: "!string_equals", input: a, value: b } } }
                    } },
                    right: %s
                } }""".formatted(F, T, T);
        assertTrue(check(nested));
        RuleSet set = load("""
                fly-rules:
                  zone:
                    priority: 1
                    result: DENY
                    conditions:
                      c: %s
                """.formatted(nested.replace("\n", " ")));
        assertEquals(1, set.size());
        assertFalse(set.evaluate(null).allowed());
    }

    @Test
    void anyVideRefuseAvecWarn() throws Exception {
        assertThrows(InvalidRuleException.class, () -> condition("{ type: any, children: {} }"));
        assertThrows(InvalidRuleException.class, () -> condition("{ type: any, children: [] }"));
        assertThrows(InvalidRuleException.class, () -> condition("{ type: any }"));
        assertThrows(InvalidRuleException.class, () -> condition("{ type: all, children: [] }"));
        RuleSet set = load("""
                fly-rules:
                  vide:
                    result: DENY
                    conditions:
                      c: { type: any, children: {} }
                  ok:
                    result: ALLOW
                """);
        assertEquals(List.of("ok"), set.rules().stream().map(Rule::name).toList());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("vide") && w.contains("any")), warnings.toString());
    }

    private static String nest(int depth) {
        String inner = T;
        for (int i = 1; i < depth; i++) {
            inner = "{ type: all, children: [ " + inner + " ] }";
        }
        return inner;
    }

    @Test
    void profondeurMaximale() throws Exception {
        assertTrue(check(nest(CompositeCondition.MAX_DEPTH)), "profondeur 8 acceptée");
        InvalidRuleException e = assertThrows(InvalidRuleException.class,
                () -> condition(nest(CompositeCondition.MAX_DEPTH + 1)));
        assertTrue(e.getMessage().contains("profondeur"), e.getMessage());
        RuleSet set = load("""
                fly-rules:
                  trop_profond:
                    result: DENY
                    conditions:
                      c: %s
                """.formatted(nest(CompositeCondition.MAX_DEPTH + 1)));
        assertTrue(set.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("trop_profond")));
    }

    @Test
    void trenteDeuxEnfantsMaximum() throws Exception {
        StringBuilder ok = new StringBuilder("{ type: any, children: [ ");
        for (int i = 0; i < CompositeCondition.MAX_CHILDREN; i++) {
            ok.append(i == 0 ? "" : ", ").append(F);
        }
        assertFalse(check(ok + " ] }"));
        assertThrows(InvalidRuleException.class, () -> condition(ok + ", " + T + " ] }"));
    }

    @Test
    void enfantInvalideRefuseLaRegle() {
        assertThrows(InvalidRuleException.class, () -> condition("{ type: any, children: { x: { type: inconnu } } }"));
        assertThrows(InvalidRuleException.class, () -> condition("{ type: any, children: { x: { input: a } } }"));
        assertThrows(InvalidRuleException.class, () -> condition("{ type: any, children: { x: notamap } }"));
        assertThrows(InvalidRuleException.class, () -> condition("{ type: any, children: notamap }"));
    }

    // ------------------------------------------------------------------ A2 : tri stable, aucune limite

    @Test
    void triStableEtAucuneLimiteDeNombre() throws Exception {
        StringBuilder sb = new StringBuilder("fly-rules:\n");
        int n = 700;
        for (int i = 0; i < n; i++) {
            // priorités 0/1 alternées : à priorité égale, l'ordre de déclaration doit être conservé
            sb.append("  r").append(i).append(": { priority: ").append(i % 2).append(", result: ALLOW }\n");
        }
        RuleSet set = load(sb.toString());
        assertEquals(n, set.size());
        List<String> names = set.rules().stream().map(Rule::name).toList();
        assertEquals("r0", names.get(0));
        assertEquals("r2", names.get(1));
        assertEquals("r1", names.get(n / 2));
        assertEquals("r" + (n - 1), names.get(n - 1));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    // ------------------------------------------------------------------ registre / API

    @Test
    void typesCompositesReserves() {
        assertThrows(IllegalArgumentException.class, () -> registry.register("any", node -> ctx -> true));
        assertThrows(IllegalArgumentException.class, () -> registry.register("AND", node -> ctx -> true));
        assertFalse(registry.unregister("string_equals"), "type intégré non retirable");
        assertFalse(registry.unregister("any"));
    }

    @Test
    void conditionTierceViaApi() throws Exception {
        com.azurhosts.aafly.api.ConditionFactory api = def -> {
            boolean expected = def.bool("expected", true);
            if (def.parameters().containsKey("negate")) {
                throw new AssertionError("negate ne doit pas être transmis");
            }
            return (FlightCondition) ctx -> expected;
        };
        registry.register("My_Level", new com.azurhosts.aafly.apiimpl.ApiConditionAdapter("my_level", api, logger));
        assertTrue(check("{ type: my_level }"));
        assertFalse(check("{ type: MY_LEVEL, expected: false }"));
        assertTrue(check("{ type: \"!my_level\", expected: false }"));
        assertTrue(check("{ type: any, children: [ %s, { type: my_level } ] }".formatted(F)));
        assertTrue(registry.unregister("my_level"));
        assertThrows(InvalidRuleException.class, () -> condition("{ type: my_level }"));
    }

    @Test
    void conditionTierceDefaillante() throws Exception {
        registry.register("bad_param", new com.azurhosts.aafly.apiimpl.ApiConditionAdapter("bad_param", def -> {
            throw new IllegalArgumentException("paramètre 'max' manquant");
        }, logger));
        InvalidRuleException e = assertThrows(InvalidRuleException.class, () -> condition("{ type: bad_param }"));
        assertTrue(e.getMessage().contains("max"), e.getMessage());

        registry.register("null_cond", new com.azurhosts.aafly.apiimpl.ApiConditionAdapter("null_cond", def -> null, logger));
        assertThrows(InvalidRuleException.class, () -> condition("{ type: null_cond }"));

        registry.register("boom", new com.azurhosts.aafly.apiimpl.ApiConditionAdapter("boom", def -> ctx -> {
            throw new IllegalStateException("boom");
        }, logger));
        Condition boom = condition("{ type: boom }");
        assertFalse(boom.test(null), "une erreur d'évaluation vaut false");
        assertFalse(boom.test(null));
        assertEquals(1, warnings.stream().filter(w -> w.contains("boom")).count(), "journal limité");
    }
}
