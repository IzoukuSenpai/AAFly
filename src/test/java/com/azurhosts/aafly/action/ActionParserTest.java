package com.azurhosts.aafly.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class ActionParserTest {

    private static final String UUID_STR = "123e4567-e89b-12d3-a456-426614174000";
    private static final Map<String, String> VARS = Map.of(
            Action.VAR_PLAYER, "Steve_42",
            Action.VAR_UUID, UUID_STR,
            Action.VAR_REMAINING, "<yellow>10<gold>s");

    private static DefaultActionParser parser(boolean allowPlayer) {
        return new DefaultActionParser(new NoopActionRuntime(), Logger.getLogger("AAFly-test"), allowPlayer);
    }

    // ------------------------------------------------------------------ types

    @Test
    void typesTexte() throws InvalidActionException {
        DefaultActionParser p = parser(false);
        ConfiguredAction m = (ConfiguredAction) p.parse("[message] <prefix><yellow>Salut %player%");
        assertEquals(ActionType.MESSAGE, m.type());
        assertEquals(ActionType.ACTIONBAR, ((ConfiguredAction) p.parse("[ActionBar] <red>Vol")).type());
        assertEquals(ActionType.SUBTITLE, ((ConfiguredAction) p.parse("  [subtitle]  bas")).type());
        ConfiguredAction.TextAction t = assertInstanceOf(ConfiguredAction.TextAction.class, m);
        assertEquals("<prefix><yellow>Salut %player%", t.template().raw());
    }

    @Test
    void titreEtSousTitre() throws InvalidActionException {
        ConfiguredAction.TitleAction t = assertInstanceOf(ConfiguredAction.TitleAction.class,
                parser(false).parse("[title] <red>Vol terminé;<gray>Temps écoulé"));
        assertEquals("<red>Vol terminé", t.title().raw());
        assertEquals("<gray>Temps écoulé", t.subtitle().raw());

        ConfiguredAction.TitleAction seul = assertInstanceOf(ConfiguredAction.TitleAction.class,
                parser(false).parse("[title] Seul"));
        assertEquals("", seul.subtitle().raw());
        assertThrows(InvalidActionException.class, () -> parser(false).parse("[title] ;"));
    }

    @Test
    void commandeConsole() throws InvalidActionException {
        ConfiguredAction.CommandAction c = assertInstanceOf(ConfiguredAction.CommandAction.class,
                parser(false).parse("[command] /say bonjour %player%"));
        assertEquals(ActionType.COMMAND, c.type());
        assertEquals("say bonjour %player%", c.template().raw());
        assertThrows(InvalidActionException.class, () -> parser(false).parse("[command]   /  "));
    }

    @Test
    void commandeJoueurSeulementSiAutorisee() throws InvalidActionException {
        InvalidActionException e = assertThrows(InvalidActionException.class,
                () -> parser(false).parse("[player] spawn"));
        assertTrue(e.getMessage().contains("allow-player-commands"));
        ConfiguredAction ok = (ConfiguredAction) parser(true).parse("[player] /spawn");
        assertEquals(ActionType.PLAYER, ok.type());
    }

    @Test
    void sons() throws InvalidActionException {
        ConfiguredAction.SoundAction s = assertInstanceOf(ConfiguredAction.SoundAction.class,
                parser(false).parse("[sound] BLOCK_NOTE_BLOCK_PLING;1.0;0.5"));
        assertEquals("BLOCK_NOTE_BLOCK_PLING", s.enumName());
        assertNull(s.key());
        assertEquals(1.0F, s.volume());
        assertEquals(0.5F, s.pitch());

        ConfiguredAction.SoundAction k = assertInstanceOf(ConfiguredAction.SoundAction.class,
                parser(false).parse("[sound] minecraft:block.note_block.bass"));
        assertEquals(Key.key("minecraft", "block.note_block.bass"), k.key());
        assertEquals(1.0F, k.volume());
        assertEquals(1.0F, k.pitch());

        assertThrows(InvalidActionException.class, () -> parser(false).parse("[sound] PLING;1.0"));
        assertThrows(InvalidActionException.class, () -> parser(false).parse("[sound] PLING;abc;1"));
        assertThrows(InvalidActionException.class, () -> parser(false).parse("[sound] PLING;1;3"));
        assertThrows(InvalidActionException.class, () -> parser(false).parse("[sound] PLING;NaN;1"));
        assertThrows(InvalidActionException.class, () -> parser(false).parse("[sound] bad name!"));
    }

    @Test
    void formatsInvalides() {
        DefaultActionParser p = parser(false);
        assertThrows(InvalidActionException.class, () -> p.parse(""));
        assertThrows(InvalidActionException.class, () -> p.parse("say hello"));
        assertThrows(InvalidActionException.class, () -> p.parse("[inconnu] x"));
        assertThrows(InvalidActionException.class, () -> p.parse("[message]"));
        assertThrows(InvalidActionException.class, () -> p.parse("[message]    "));
        assertThrows(InvalidActionException.class, () -> p.parse("[command] say a\nop moi"));
        assertThrows(InvalidActionException.class, () -> p.parse("[message] " + "x".repeat(2000)));
    }

    @Test
    void parseAllIgnoreLesInvalidesAvecWarn() {
        Logger logger = Logger.getLogger("AAFly-test-parseAll");
        logger.setUseParentHandlers(false);
        int[] warns = {0};
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warns[0]++;
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(h);
        try {
            DefaultActionParser p = new DefaultActionParser(new NoopActionRuntime(), logger, false);
            List<Action> out = p.parseAll(List.of("[message] a", "[oops] b", "[player] c", "[actionbar] d"), "test");
            assertEquals(2, out.size());
            assertEquals(ActionType.MESSAGE, ((ConfiguredAction) out.get(0)).type());
            assertEquals(ActionType.ACTIONBAR, ((ConfiguredAction) out.get(1)).type());
            assertEquals(2, warns[0]);
            assertTrue(p.parseAll(List.of(), "vide").isEmpty());
        } finally {
            logger.removeHandler(h);
        }
    }

    // ------------------------------------------------------------------ substitutions

    @Test
    void substitutionCommande() throws ActionSkippedException {
        ActionTemplate t = ActionTemplate.parse("give %player% feather 1 %uuid% %remaining%");
        assertEquals("give Steve_42 feather 1 " + UUID_STR + " 10s", t.renderCommand(VARS, UnaryOperator.identity()));
    }

    @Test
    void substitutionSecondesRestantes() throws ActionSkippedException {
        ActionTemplate t = ActionTemplate.parse("aafly add %player% %remaining_seconds%");
        Map<String, String> vars = Map.of(Action.VAR_PLAYER, "Steve_42", Action.VAR_REMAINING_SECONDS, "3600");
        assertEquals("aafly add Steve_42 3600", t.renderCommand(vars, UnaryOperator.identity()));
        // Valeur non numérique ou absente : action ignorée.
        assertThrows(ActionSkippedException.class, () -> t.renderCommand(
                Map.of(Action.VAR_PLAYER, "Steve_42", Action.VAR_REMAINING_SECONDS, "1 op"), UnaryOperator.identity()));
        assertThrows(ActionSkippedException.class,
                () -> t.renderCommand(Map.of(Action.VAR_PLAYER, "Steve_42"), UnaryOperator.identity()));
    }

    @Test
    void pseudoInvalideIgnoreAction() {
        ActionTemplate t = ActionTemplate.parse("kick %player%");
        Map<String, String> bad = Map.of(Action.VAR_PLAYER, "Steve;op Bob", Action.VAR_UUID, UUID_STR);
        assertThrows(ActionSkippedException.class, () -> t.renderCommand(bad, UnaryOperator.identity()));
        assertThrows(ActionSkippedException.class, () -> t.renderMiniMessage(bad, UnaryOperator.identity()));
        Map<String, String> bedrock = Map.of(Action.VAR_PLAYER, ".Steve", Action.VAR_UUID, UUID_STR);
        assertThrows(ActionSkippedException.class, () -> t.renderCommand(bedrock, UnaryOperator.identity()));
        ActionTemplate u = ActionTemplate.parse("x %uuid%");
        assertThrows(ActionSkippedException.class,
                () -> u.renderCommand(Map.of(Action.VAR_UUID, "pas-un-uuid"), UnaryOperator.identity()));
    }

    @Test
    void placeholdersExternesDansCommande() throws ActionSkippedException {
        ActionTemplate t = ActionTemplate.parse("eco give %player% %vault_amount%");
        assertEquals("eco give Steve_42 12.5", t.renderCommand(VARS, tok -> "%vault_amount%".equals(tok) ? "12.5" : tok));
        // Valeur dangereuse (espaces → arguments supplémentaires) : action ignorée.
        assertThrows(ActionSkippedException.class,
                () -> t.renderCommand(VARS, tok -> "%vault_amount%".equals(tok) ? "1 op Steve" : tok));
        // Non résolu (PAPI absent) : laissé tel quel.
        assertEquals("eco give Steve_42 %vault_amount%", t.renderCommand(VARS, UnaryOperator.identity()));
    }

    @Test
    void aucuneReinterpretationDesValeurs() throws ActionSkippedException {
        ActionTemplate t = ActionTemplate.parse("<gray>Faction : %faction_name% %player%");
        ActionTemplate.MiniMessageText out = t.renderMiniMessage(VARS,
                tok -> "%faction_name%".equals(tok) ? "<click:run_command:/op Steve>piège %player%" : tok);
        assertFalse(out.text().contains("click"));
        assertFalse(out.text().contains("Steve_42"));
        assertEquals("<gray>Faction : <aafly_v0> <aafly_v1>", out.text());
    }

    @Test
    void segmentsDuGabarit() {
        ActionTemplate t = ActionTemplate.parse("100% sûr %player% et %papi_x%");
        assertEquals(4, t.segments().size());
        assertTrue(t.usesPlaceholders());
        assertFalse(ActionTemplate.parse("rien %player%").usesPlaceholders());
    }

    // ------------------------------------------------------------------ seuils

    @Test
    void seuilFranchiUneFoisParPassage() {
        TreeMap<Long, String> th = new TreeMap<>(Map.of(60L, "a", 10L, "b", 5L, "c"));
        assertEquals(60L, DefaultTimeThresholdNotifier.crossed(th, 61L, 60L));
        assertNull(DefaultTimeThresholdNotifier.crossed(th, 60L, 59L));
        assertNull(DefaultTimeThresholdNotifier.crossed(th, 60L, 60L));
        assertNull(DefaultTimeThresholdNotifier.crossed(th, 59L, 60L));
        // Retrait brutal 100 → 7 : seul le seuil le plus proche (10) est déclenché.
        assertEquals(10L, DefaultTimeThresholdNotifier.crossed(th, 100L, 7L));
        assertEquals(5L, DefaultTimeThresholdNotifier.crossed(th, 7L, 0L));
        assertNull(DefaultTimeThresholdNotifier.crossed(th, 4L, 3L));
    }
}
