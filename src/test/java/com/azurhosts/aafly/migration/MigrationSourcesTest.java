package com.azurhosts.aafly.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.config.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sources de migration : TempFly (YAML sûr), configuration wFly (mysql jamais loggée, redis ignoré),
 * chemins fixes sans lien symbolique, validation des lignes, syntaxe de la commande.
 */
class MigrationSourcesTest {

    private static final Logger LOG = Logger.getLogger("AAFly-test-sources");

    @TempDir
    Path plugins;

    private Path write(String dirName, String file, String content) throws IOException {
        Path d = Files.createDirectories(plugins.resolve(dirName));
        return Files.writeString(d.resolve(file), content, StandardCharsets.UTF_8);
    }

    @Test
    void tempFlyPlayersTimeIsRead() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        write("TempFly", "data.yml", "players:\n  " + a + ":\n    time: 3600\n    logged_in_flight: true\n"
                + "  " + b + ":\n    time: -1\n  pas-un-uuid:\n    time: 5\n");
        List<SourceRow> rows = MigrationSources.readTempFly(plugins);
        assertEquals(3, rows.size());
        long valid = rows.stream().filter(r -> r.validate(315_360_000L).isPresent()).count();
        assertEquals(1, valid);
        assertEquals(3600L, rows.stream().map(r -> r.validate(315_360_000L)).flatMap(Optional::stream)
                .findFirst().orElseThrow().seconds());
    }

    @Test
    void tempFlyUnknownFormatIsRefused() throws Exception {
        write("TempFly", "data.yml", "joueurs:\n  x: 1\n");
        MigrationException e = assertThrows(MigrationException.class, () -> MigrationSources.readTempFly(plugins));
        assertTrue(e.getMessage().contains("format non reconnu"));
    }

    @Test
    void yamlObjectTagsAreNeverInstantiated() throws Exception {
        write("TempFly", "data.yml", "players: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader "
                + "[[!!java.net.URL [\"http://127.0.0.1/\"]]]]\n");
        assertThrows(MigrationException.class, () -> MigrationSources.readTempFly(plugins));
    }

    @Test
    void yamlAliasBombIsRejected() throws Exception {
        StringBuilder sb = new StringBuilder("a: &a [x,x,x,x,x,x,x,x,x,x]\n");
        for (int i = 0; i < 60; i++) {
            sb.append("b").append(i).append(": [*a,*a,*a,*a,*a,*a,*a,*a,*a,*a]\n");
        }
        sb.append("players: {}\n");
        write("TempFly", "data.yml", sb.toString());
        assertThrows(MigrationException.class, () -> MigrationSources.readTempFly(plugins));
    }

    @Test
    void wflyMysqlSectionIsParsedAndPasswordNeverPrinted() {
        Map<String, Object> mysql = Map.of("host", "db.example", "port", 3307, "database", "wfly",
                "user", "wfly_user", "password", "S3cr3t!");
        Settings.MysqlSection s = MigrationSources.wflyMysqlSection(Map.of("mysql", mysql));
        assertEquals("db.example", s.host());
        assertEquals(3307, s.port());
        assertEquals("wfly", s.database());
        assertEquals("wfly_user", s.username());
        assertEquals("S3cr3t!", s.password());
        assertFalse(s.toString().contains("S3cr3t!"));
        assertThrows(MigrationException.class, () -> MigrationSources.wflyMysqlSection(Map.of("other", 1)));
    }

    @Test
    void redisKeyIsIgnoredWithWarning() {
        Logger logger = Logger.getLogger("AAFly-test-redis");
        logger.setUseParentHandlers(false);
        StringBuilder seen = new StringBuilder();
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                seen.append(r.getLevel()).append(':').append(r.getMessage());
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
            assertTrue(MigrationSources.warnIfRedis(Map.of("redis", Map.of("enabled", true)), logger));
            assertTrue(seen.toString().startsWith("WARNING:"));
            assertFalse(MigrationSources.warnIfRedis(Map.of("mysql", Map.of()), logger));
        } finally {
            logger.removeHandler(h);
        }
    }

    @Test
    void symbolicLinksAreNeverFollowed() throws Exception {
        Path outside = Files.createTempFile("aafly-outside", ".db");
        Path link = Files.createDirectories(plugins.resolve("wFlyV2")).resolve("database.db");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("liens symboliques non disponibles ici : " + e.getClass().getSimpleName());
        } finally {
        }
        try {
            MigrationException e = assertThrows(MigrationException.class,
                    () -> SafePaths.resolveFile(plugins, "wFlyV2", "database.db"));
            assertTrue(e.getMessage().contains("lien"));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void safePathsRejectsTraversalAndMissingFiles() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SafePaths.resolveFile(plugins, "..", "x"));
        assertThrows(IllegalArgumentException.class, () -> SafePaths.resolveFile(plugins, "a/b"));
        assertNull(SafePaths.resolveOptionalFile(plugins, "wFlyV2", "config.yml"));
        assertThrows(MigrationException.class, () -> SafePaths.resolveFile(plugins, "wFlyV2", "config.yml"));
        Files.createDirectories(plugins.resolve("TempFly").resolve("data.yml"));
        assertThrows(MigrationException.class, () -> SafePaths.resolveFile(plugins, "TempFly", "data.yml"),
                "un dossier n'est pas un fichier ordinaire");
    }

    @Test
    void rowValidation() {
        long max = 1000L;
        UUID id = UUID.randomUUID();
        assertTrue(new SourceRow(id.toString(), 10L, true).validate(max).orElseThrow().flying());
        assertEquals(id, new SourceRow(id.toString().replace("-", "").toUpperCase(), "10", 0).validate(max)
                .orElseThrow().uuid());
        assertEquals(1000L, new SourceRow(id, 1000.0D, null).validate(max).orElseThrow().seconds());
        assertTrue(new SourceRow(id.toString(), 1001L, null).validate(max).isEmpty(), "au-delà du plafond");
        assertTrue(new SourceRow(id.toString(), -1, null).validate(max).isEmpty());
        assertTrue(new SourceRow(id.toString(), 1.5D, null).validate(max).isEmpty());
        assertTrue(new SourceRow(id.toString(), Double.NaN, null).validate(max).isEmpty());
        assertTrue(new SourceRow(id.toString(), "1e3", null).validate(max).isEmpty());
        assertTrue(new SourceRow("1-1-1-1-1", 5L, null).validate(max).isEmpty(), "forme laxiste de UUID refusée");
        assertTrue(new SourceRow(null, 5L, null).validate(max).isEmpty());
        assertFalse(new SourceRow(id.toString(), 5L, "oui").validate(max).orElseThrow().flying());
    }

    @Test
    void commandSyntax() {
        var r = MigrateCommand.parse(new String[] {"migrate", "wfly"}).orElseThrow();
        assertEquals(MigrationService.Source.WFLY_SQLITE, r.source());
        assertEquals(MergeStrategy.MAX, r.strategy());
        assertFalse(r.dryRun());

        r = MigrateCommand.parse(new String[] {"migrate", "wfly", "mysql", "--merge=replace", "--dry-run"}).orElseThrow();
        assertEquals(MigrationService.Source.WFLY_MYSQL, r.source());
        assertEquals(MergeStrategy.REPLACE, r.strategy());
        assertTrue(r.dryRun());

        r = MigrateCommand.parse(new String[] {"migrate", "TempFly", "--MERGE=skip"}).orElseThrow();
        assertEquals(MigrationService.Source.TEMPFLY, r.source());
        assertEquals(MergeStrategy.SKIP, r.strategy());

        assertTrue(MigrateCommand.parse(new String[] {"migrate"}).isEmpty());
        assertTrue(MigrateCommand.parse(new String[] {"migrate", "tempfly", "mysql"}).isEmpty());
        assertTrue(MigrateCommand.parse(new String[] {"migrate", "wfly", "--merge=sum"}).isEmpty());
        assertTrue(MigrateCommand.parse(new String[] {"migrate", "wfly", "../../etc/passwd"}).isEmpty());
        assertTrue(MigrateCommand.parse(new String[] {"migrate", "essentials"}).isEmpty());
    }
}
