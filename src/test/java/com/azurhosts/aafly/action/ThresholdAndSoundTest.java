package com.azurhosts.aafly.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.config.ConfigException;
import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.config.MessagesConfig;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** SPEC-phase2 A7 (actions compilées, sons validés, origine) et A8 (forme héritée des seuils). */
class ThresholdAndSoundTest {

    private static ConfigurationNode yaml(String text) throws ConfigurateException {
        return YamlConfigurationLoader.builder().buildAndLoadString(text);
    }

    // ------------------------------------------------------------------ A8

    @Test
    void formeHeriteeWflyConvertieEnCommand() throws Exception {
        MessagesConfig m = MessagesConfig.from(yaml("""
                prefix: ""
                commands-time-remaining:
                  60:
                    - "[title] <red>1 minute;<gray>de vol"
                    - "[sound] BLOCK_NOTE_BLOCK_PLING"
                  30:
                    commands: "say %player% plus que 30 s"
                  10:
                    commands:
                      - "/broadcast 10 s"
                      - "[message] deja typee"
                    autre: ignoree
                """));
        assertEquals(List.of(10L, 30L, 60L), List.copyOf(m.timeRemaining().keySet()));
        assertEquals(List.of("[command] say %player% plus que 30 s"), m.timeRemainingActions(30L));
        assertEquals(List.of("[command] /broadcast 10 s", "[message] deja typee"), m.timeRemainingActions(10L));
        assertEquals(2, m.timeRemainingActions(60L).size());

        DefaultActionParser parser = new DefaultActionParser(new NoopActionRuntime(), Logger.getLogger("AAFly-test"), false);
        ConfiguredAction.CommandAction c = assertInstanceOf(ConfiguredAction.CommandAction.class,
                parser.parse(m.timeRemainingActions(10L).get(0)));
        assertEquals(ActionType.COMMAND, c.type());
        assertEquals("broadcast 10 s", c.template().raw(), "le / initial est retiré");
    }

    @Test
    void formeHeriteeInvalideRefusee() {
        assertThrows(ConfigException.class, () -> MessagesConfig.from(yaml("""
                commands-time-remaining:
                  30:
                    message: "pas de commands"
                """)));
        assertThrows(ConfigException.class, () -> MessagesConfig.from(yaml("""
                commands-time-remaining:
                  30:
                    commands: { a: b }
                """)));
    }

    @Test
    void conversionUnitaire() {
        assertEquals("[command] spawn", ThresholdActions.convertLegacy("  spawn "));
        assertEquals("[actionbar] x", ThresholdActions.convertLegacy("[actionbar] x"));
        assertEquals("[command] [inconnu] x", ThresholdActions.convertLegacy("[inconnu] x"));
    }

    // ------------------------------------------------------------------ A7

    /** Runtime de test connaissant deux sons. */
    private static final class SoundRuntime implements ActionRuntime {
        private static final Key PLING = Key.key("minecraft", "block.note_block.pling");
        private static final Key BASS = Key.key("minecraft", "block.note_block.bass");
        private final Logger logger;
        final List<String> warned = new ArrayList<>();

        SoundRuntime(Logger logger) {
            this.logger = logger;
        }

        @Override
        public Lang lang() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PlaceholderBridge placeholders() {
            return PlaceholderBridge.disabled();
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public void dispatchConsole(String command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isPrimaryThread() {
            return true;
        }

        @Override
        public void runOnMain(Runnable task) {
            task.run();
        }

        @Override
        public Optional<Key> resolveSoundName(String enumName) {
            return "BLOCK_NOTE_BLOCK_PLING".equals(enumName) ? Optional.of(PLING) : Optional.empty();
        }

        @Override
        public Set<Key> knownSounds() {
            return Set.of(PLING, BASS);
        }

        @Override
        public String remaining(Player player) {
            return null;
        }

        @Override
        public void warnOnce(String key, String message) {
            warned.add(message);
        }
    }

    private static Logger capture(List<String> out) {
        Logger logger = Logger.getLogger("AAFly-test-actions-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    out.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    @Test
    void sonsValidesAuChargement() throws Exception {
        List<String> warnings = new ArrayList<>();
        DefaultActionParser p = new DefaultActionParser(new SoundRuntime(capture(warnings)), capture(warnings), false);
        ConfiguredAction.SoundAction s = assertInstanceOf(ConfiguredAction.SoundAction.class,
                p.parse("[sound] BLOCK_NOTE_BLOCK_PLING;1;1"));
        assertEquals(SoundRuntime.PLING, s.key(), "nom enum résolu au chargement");
        assertInstanceOf(ConfiguredAction.SoundAction.class, p.parse("[sound] minecraft:block.note_block.bass"));
        assertInstanceOf(ConfiguredAction.SoundAction.class, p.parse("[sound] monpack:custom.sound"),
                "son de pack de ressources non vérifiable : accepté");
        InvalidActionException e = assertThrows(InvalidActionException.class, () -> p.parse("[sound] NOT_A_SOUND"));
        assertTrue(e.getMessage().contains("inconnu"));
        assertThrows(InvalidActionException.class, () -> p.parse("[sound] minecraft:not.a.sound"));

        List<Action> list = p.parseAll(List.of("[sound] NOT_A_SOUND", "[message] ok"), "fly-rules.no_combat.actions");
        assertEquals(1, list.size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("fly-rules.no_combat.actions") && w.contains("NOT_A_SOUND")
                && w.contains("inconnu")), warnings.toString());
    }

    @Test
    void sansListeDeSonsResolutionTardive() throws Exception {
        DefaultActionParser p = new DefaultActionParser(new NoopActionRuntime(), Logger.getLogger("AAFly-test"), false);
        ConfiguredAction.SoundAction s = assertInstanceOf(ConfiguredAction.SoundAction.class,
                p.parse("[sound] WHATEVER_SOUND"));
        assertNull(s.key());
        assertEquals("WHATEVER_SOUND", s.enumName());
    }

    @Test
    void origineMemorisee() throws Exception {
        DefaultActionParser p = new DefaultActionParser(new NoopActionRuntime(), Logger.getLogger("AAFly-test"), false);
        List<Action> list = p.parseAll(List.of("[message] a", "[command] b", "[title] c", "[sound] minecraft:x"),
                "fly-rules.zone.actions");
        assertEquals(4, list.size());
        for (Action a : list) {
            assertEquals("fly-rules.zone.actions", a.origin());
        }
        assertEquals("config", p.parse("[message] a").origin());
        assertEquals("", ((Action) (player, vars) -> { }).origin());
    }
}
