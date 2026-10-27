package com.azurhosts.aafly.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.core.DecrementMode;
import com.azurhosts.aafly.storage.StorageType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigManagerTest {

    private static final Logger LOGGER = Logger.getLogger("AAFly-test");

    private static ConfigManager manager(Path dir) {
        return new ConfigManager(dir, LOGGER, name -> ConfigManagerTest.class.getClassLoader().getResourceAsStream(name));
    }

    @Test
    void valeursParDefaut(@TempDir Path dir) throws ConfigException {
        ConfigManager cm = manager(dir);
        cm.load();
        assertTrue(Files.exists(dir.resolve(ConfigManager.CONFIG_FILE)));
        assertTrue(Files.exists(dir.resolve(ConfigManager.MESSAGES_FILE)));

        Settings s = cm.settings();
        assertEquals(1, s.configVersion());
        assertFalse(s.debug());
        assertEquals(StorageType.SQLITE, s.storage().type());
        assertEquals(300, s.storage().saveIntervalSeconds());
        assertEquals(3306, s.storage().mysql().port());
        assertEquals(6, s.storage().mysql().poolSize());
        assertFalse(s.storage().mysql().useSsl());
        assertEquals(315_360_000L, s.limits().maxSeconds());
        assertEquals(DecrementMode.FLYING_MODE, s.decrement().mode());
        assertEquals(3, s.decrement().staticDelaySeconds());
        assertTrue(s.flight().tpOnFloorWhenDisabled());
        assertFalse(s.flight().unlimitedForOp());
        assertEquals(0.1D, s.flight().defaultSpeed());
        assertEquals("<gold>Vol : <yellow>%aafly_remaining%", s.actionbar().message());
        assertEquals(5, s.pvp().radius());
        assertEquals(List.of(), s.pvp().bypassPlaceholders());
        assertEquals(3_600L, s.give().maxSeconds());
        assertEquals("FEATHER", s.token().material());
        assertEquals(2, s.token().lore().size());
        assertEquals(2, s.timedFly().groups().size());
        assertEquals("vip", s.timedFly().groups().get(0).name());
        assertEquals(120L, s.timedFly().groups().get(0).flyTime());
        assertEquals(60L, s.timedFly().groups().get(1).cooldown());
        assertEquals("<yellow>%days%<gold>j", s.format().units().days());
        assertFalse(s.actions().allowPlayerCommands());
        assertTrue(s.compat().wflyPlaceholders());
        assertTrue(s.flyRules().isMap());
        assertEquals(999, s.flyRules().node("default", "priority").getInt());
        Settings.MysqlSection secret = new Settings.MysqlSection("h", 3306, "d", "u", "s3cr3t!", false, 2);
        assertFalse(secret.toString().contains("s3cr3t!"), "mot de passe masqué attendu");

        MessagesConfig m = cm.messages();
        assertTrue(m.prefix().contains("AAFly"));
        assertTrue(m.has("errors.no-permission"));
        assertTrue(m.isList("admin.status"));
        assertEquals(List.of(1L, 2L, 3L, 5L, 10L, 30L, 60L), List.copyOf(m.timeRemaining().keySet()));
        assertEquals(2, m.timeRemainingActions(10L).size());
        assertEquals(1, m.list(MessagesConfig.KEY_ON_EXPIRE).size());
    }

    @Test
    void clesManquantesAjouteesSansEcraser(@TempDir Path dir) throws ConfigException, IOException {
        Files.writeString(dir.resolve(ConfigManager.CONFIG_FILE), """
                storage:
                  type: mysql
                  mysql:
                    host: db.example.net
                pvp:
                  radius: 12
                fly-rules:
                  only_one: { priority: 1, result: DENY }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(ConfigManager.MESSAGES_FILE), """
                prefix: "&a[Test] "
                commands-time-remaining:
                  15: ["[message] test"]
                """, StandardCharsets.UTF_8);
        ConfigManager cm = manager(dir);
        cm.load();
        Settings s = cm.settings();
        assertEquals(StorageType.MYSQL, s.storage().type());
        assertEquals("db.example.net", s.storage().mysql().host());
        assertEquals(3306, s.storage().mysql().port(), "clé manquante complétée");
        assertEquals(12, s.pvp().radius(), "valeur utilisateur conservée");
        assertEquals(1, s.flyRules().childrenMap().size(), "les règles ne sont pas fusionnées");
        assertTrue(s.decrementRules().virtual() || s.decrementRules().isMap() || s.decrementRules().empty());

        MessagesConfig m = cm.messages();
        assertEquals("&a[Test] ", m.prefix());
        assertEquals(List.of(15L), List.copyOf(m.timeRemaining().keySet()), "seuils non fusionnés");
        assertTrue(m.has("fly.enabled"), "message manquant ajouté");

        String rewritten = Files.readString(dir.resolve(ConfigManager.CONFIG_FILE), StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("save-interval-seconds"));
        assertTrue(Files.exists(dir.resolve(ConfigManager.CONFIG_FILE + ".bak")));
    }

    @Test
    void bornesInvalides(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ConfigManager.CONFIG_FILE), """
                pvp:
                  radius: 500
                flight:
                  default-speed: 3.0
                give:
                  min-seconds: 100
                  max-seconds: 10
                """, StandardCharsets.UTF_8);
        ConfigException e = assertThrows(ConfigException.class, () -> manager(dir).load());
        assertTrue(e.getMessage().contains("pvp.radius"));
        assertTrue(e.getMessage().contains("flight.default-speed"));
        assertTrue(e.getMessage().contains("give.max-seconds"));
    }

    @Test
    void typesInvalides(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ConfigManager.CONFIG_FILE), """
                storage:
                  type: POSTGRES
                """, StandardCharsets.UTF_8);
        assertThrows(ConfigException.class, () -> manager(dir).load());
    }

    @Test
    void yamlCasse(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ConfigManager.CONFIG_FILE), "storage: [\n  :::", StandardCharsets.UTF_8);
        assertThrows(ConfigException.class, () -> manager(dir).load());
    }

    @Test
    void seuilInvalide(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ConfigManager.MESSAGES_FILE), """
                commands-time-remaining:
                  abc: ["[message] x"]
                """, StandardCharsets.UTF_8);
        assertThrows(ConfigException.class, () -> manager(dir).load());
    }

    @Test
    void rechargementAtomique(@TempDir Path dir) throws ConfigException, IOException {
        ConfigManager cm = manager(dir);
        cm.load();
        Settings before = cm.settings();
        Files.writeString(dir.resolve(ConfigManager.CONFIG_FILE), """
                rules:
                  check-interval-seconds: 0
                """, StandardCharsets.UTF_8);
        assertThrows(ConfigException.class, cm::reload);
        assertEquals(before, cm.settings(), "l'ancienne configuration doit être conservée");
    }

    @Test
    void groupesTimedFlyEnDouble(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ConfigManager.CONFIG_FILE), """
                timed-fly:
                  enabled: true
                  groups:
                    - { name: vip, fly-time: 10, cooldown: 5 }
                    - { name: vip, fly-time: 20, cooldown: 5 }
                """, StandardCharsets.UTF_8);
        ConfigException e = assertThrows(ConfigException.class, () -> manager(dir).load());
        assertTrue(e.getMessage().contains("en double"));
    }

    @Test
    void clesInconnuesSignaleesSansSuppression() throws Exception {
        org.spongepowered.configurate.ConfigurationNode defaults = org.spongepowered.configurate.BasicConfigurationNode.root();
        defaults.node("flight", "default-speed").set(0.1D);
        defaults.node("fly-rules", "default", "result").set("ALLOW");
        org.spongepowered.configurate.ConfigurationNode user = org.spongepowered.configurate.BasicConfigurationNode.root();
        user.node("flight", "default-speed").set(0.2D);
        user.node("flight", "typo").set(true);
        user.node("redis", "host").set("x");
        user.node("fly-rules", "ma_regle", "result").set("DENY");
        java.util.List<String> unknown = new java.util.ArrayList<>();
        ConfigManager.collectUnknown(user, defaults, "", java.util.Set.of("fly-rules"), unknown);
        assertEquals(List.of("flight.typo", "redis"), unknown);
        assertFalse(user.node("redis").virtual());
    }
}
