package com.azurhosts.aafly.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.core.FlightProfileSnapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Migration de schéma versionnée (phase 1 → 2) et verrou optimiste, sur SQLite (SPEC-phase2 A5).
 */
class SchemaMigrationTest {

    private static final Logger LOG = Logger.getLogger("AAFly-test");

    @TempDir
    Path dir;

    private ExecutorService io;
    private SqliteProfileRepository repo;
    private SqliteProfileRepository other;

    @AfterEach
    void tearDown() throws Exception {
        if (repo != null) {
            repo.close();
        }
        if (other != null) {
            other.close();
        }
        if (io != null) {
            io.shutdown();
            assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    private Path db() {
        return dir.resolve("aafly.db");
    }

    private Connection raw() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + db());
    }

    /** Schéma exact de la phase 1 (version 1), avec une ligne de données. */
    private void createPhase1Schema(UUID id) throws Exception {
        try (Connection c = raw(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE aafly_schema (version INTEGER NOT NULL PRIMARY KEY)");
            st.execute("CREATE TABLE aafly_profiles (uuid CHAR(36) NOT NULL PRIMARY KEY,"
                    + " name VARCHAR(16) NOT NULL DEFAULT '', remaining_seconds BIGINT NOT NULL DEFAULT 0,"
                    + " flight_enabled BOOLEAN NOT NULL DEFAULT 0, last_update BIGINT NOT NULL DEFAULT 0,"
                    + " cooldown_until BIGINT NOT NULL DEFAULT 0, fly_speed DOUBLE NOT NULL DEFAULT 0.1)");
            st.execute("CREATE INDEX idx_aafly_profiles_name ON aafly_profiles (name COLLATE NOCASE)");
            st.execute("CREATE TABLE aafly_tokens (token_id CHAR(36) NOT NULL PRIMARY KEY, seconds BIGINT NOT NULL,"
                    + " created_by CHAR(36) NULL, created_at BIGINT NOT NULL, redeemed_by CHAR(36) NULL,"
                    + " redeemed_at BIGINT NULL)");
            st.execute("INSERT INTO aafly_schema (version) VALUES (1)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO aafly_profiles VALUES (?, 'Dylan', 600, 1, 50, 7, 0.4)")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
        }
    }

    private void open() throws Exception {
        io = DefaultStorageManager.newIoExecutor(LOG);
        repo = new SqliteProfileRepository(db(), io, LOG);
        get(repo.init());
    }

    private long versionOf(UUID id) throws Exception {
        try (Connection c = raw(); PreparedStatement ps = c.prepareStatement("SELECT version FROM aafly_profiles WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    @Test
    void upgradesPhase1SchemaAndKeepsData() throws Exception {
        UUID id = UUID.randomUUID();
        createPhase1Schema(id);
        open();
        try (Connection c = raw()) {
            assertEquals(2, SchemaMigrator.currentVersion(c));
            assertEquals(SchemaMigrator.LATEST_VERSION, SchemaMigrator.currentVersion(c));
            assertTrue(SchemaMigrator.columnExists(c, "aafly_profiles", "version"));
            assertTrue(SchemaMigrator.columnExists(c, "aafly_server", "key"));
            assertTrue(SchemaMigrator.columnExists(c, "aafly_server", "value"));
        }
        assertEquals(0L, versionOf(id), "lignes existantes : version 0");
        FlightProfileSnapshot s = get(repo.find(id)).orElseThrow();
        assertEquals(new FlightProfileSnapshot(id, "Dylan", 600L, true, 50L, 7L, 0.4D), s);

        // Première écriture après migration : version 0 → 1.
        get(repo.upsert(new FlightProfileSnapshot(id, "Dylan", 590L, true, 60L, 7L, 0.4D)));
        assertEquals(1L, versionOf(id));
        assertEquals(590L, get(repo.find(id)).orElseThrow().remainingSeconds());
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        UUID id = UUID.randomUUID();
        createPhase1Schema(id);
        open();
        repo.close();
        repo = new SqliteProfileRepository(db(), io, LOG);
        get(repo.init()); // aucune étape rejouée en erreur
        try (Connection c = raw();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM aafly_schema");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1), "une ligne par version");
        }
        assertEquals(600L, get(repo.find(id)).orElseThrow().remainingSeconds());
    }

    @Test
    void interruptedMigrationIsReplayedSafely() throws Exception {
        // Arrêt brutal simulé : colonne déjà ajoutée mais version 2 non enregistrée.
        UUID id = UUID.randomUUID();
        createPhase1Schema(id);
        try (Connection c = raw(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE aafly_profiles ADD COLUMN version BIGINT NOT NULL DEFAULT 0");
        }
        open();
        try (Connection c = raw()) {
            assertEquals(2, SchemaMigrator.currentVersion(c));
            assertTrue(SchemaMigrator.columnExists(c, "aafly_server", "key"));
        }
        assertEquals(600L, get(repo.find(id)).orElseThrow().remainingSeconds());
    }

    @Test
    void freshDatabaseGoesStraightToLatest() throws Exception {
        open();
        try (Connection c = raw()) {
            assertEquals(SchemaMigrator.LATEST_VERSION, SchemaMigrator.currentVersion(c));
            assertTrue(SchemaMigrator.columnExists(c, "aafly_profiles", "version"));
        }
    }

    @Test
    void serverValuesRoundTrip() throws Exception {
        open();
        assertEquals(OptionalLong.empty(), get(repo.serverValue(AbstractSqlProfileRepository.KEY_SHUTDOWN_AT)));
        get(repo.putServerValue(AbstractSqlProfileRepository.KEY_SHUTDOWN_AT, 1_700_000_000L));
        get(repo.putServerValue(AbstractSqlProfileRepository.KEY_SHUTDOWN_AT, 1_700_000_123L));
        assertEquals(OptionalLong.of(1_700_000_123L), get(repo.serverValue(AbstractSqlProfileRepository.KEY_SHUTDOWN_AT)));
        assertThrows(IllegalArgumentException.class, () -> repo.putServerValue("x'; DROP TABLE aafly_server;--", 1L));
        assertThrows(IllegalArgumentException.class, () -> repo.serverValue("A".repeat(65)));
    }

    @Test
    void optimisticLockReappliesLocalDeltaOnConflict() throws Exception {
        UUID id = UUID.randomUUID();
        open();
        get(repo.upsert(new FlightProfileSnapshot(id, "Pilot", 100L, true, 1L, 0L, 0.1D)));

        // Deux « serveurs » sur la même base, chacun avec son exécuteur et sa base de synchronisation.
        ExecutorService io2 = DefaultStorageManager.newIoExecutor(LOG);
        try {
            other = new SqliteProfileRepository(db(), io2, LOG);
            get(other.init());
            assertEquals(100L, get(other.find(id)).orElseThrow().remainingSeconds());

            // Serveur A : le joueur consomme 10 s (100 → 90).
            get(repo.upsert(new FlightProfileSnapshot(id, "Pilot", 90L, true, 2L, 0L, 0.1D)));
            // Serveur B (base périmée : 100) : un admin ajoute 50 s (100 → 150). Conflit → 90 + 50 = 140.
            assertEquals(Set.of(), get(other.saveReporting(java.util.List.of(
                    new FlightProfileSnapshot(id, "Pilot", 150L, false, 3L, 0L, 0.2D)))));
            assertEquals(140L, get(repo.findAll()).getFirst().remainingSeconds());

            // Serveur A continue depuis sa mémoire (90 → 80) : conflit → 140 - 10 = 130 (l'ajout de B est conservé).
            get(repo.upsert(new FlightProfileSnapshot(id, "Pilot", 80L, true, 4L, 0L, 0.1D)));
            assertEquals(130L, get(repo.findAll()).getFirst().remainingSeconds());
            // B réécrit sa mémoire inchangée (150) : aucun delta, la valeur en base est gardée.
            get(other.upsert(new FlightProfileSnapshot(id, "Pilot", 150L, false, 5L, 0L, 0.2D)));
            assertEquals(130L, get(repo.findAll()).getFirst().remainingSeconds());
        } finally {
            other.close();
            other = null;
            io2.shutdown();
            assertTrue(io2.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rowCreatedConcurrentlyIsMergedNotOverwritten() throws Exception {
        UUID id = UUID.randomUUID();
        open();
        assertTrue(get(repo.find(id)).isEmpty()); // base « ligne absente »
        // Un autre écrivain crée la ligne (ex. import) avec 500 s.
        try (Connection c = raw(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO aafly_profiles (uuid, name, remaining_seconds, flight_enabled, last_update,"
                        + " cooldown_until, fly_speed, version) VALUES (?, '', 500, 0, 1, 0, 0.1, 1)")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
        // Le profil local (créé à 0) a reçu 20 s : 500 + 20.
        get(repo.upsert(new FlightProfileSnapshot(id, "New", 20L, false, 2L, 0L, 0.1D)));
        assertEquals(520L, get(repo.findAll()).getFirst().remainingSeconds());
    }

    @Test
    void releaseForgetsBase() throws Exception {
        UUID id = UUID.randomUUID();
        open();
        get(repo.upsert(new FlightProfileSnapshot(id, "A", 10L, false, 1L, 0L, 0.1D)));
        assertEquals(1, repo.trackedCount());
        repo.release(id);
        get(repo.findAll()); // file I/O vidée
        assertEquals(0, repo.trackedCount());
    }

    @Test
    void mergeSaturatesAndNeverGoesNegative() {
        var base = new AbstractSqlProfileRepository.Base(3L, 50L, 100L);
        assertEquals(0L, AbstractSqlProfileRepository.merge(base, 10L)); // 50 + (10 - 100) < 0
        assertEquals(70L, AbstractSqlProfileRepository.merge(base, 120L));
        var big = new AbstractSqlProfileRepository.Base(3L, Long.MAX_VALUE - 1L, 0L);
        assertEquals(Long.MAX_VALUE, AbstractSqlProfileRepository.merge(big, 10L));
        assertEquals(50L, AbstractSqlProfileRepository.merge(base, 100L));
    }

    @Test
    void backoffIsExponentialAndBounded() {
        assertEquals(5L, DefaultStorageManager.backoffSeconds(1));
        assertEquals(10L, DefaultStorageManager.backoffSeconds(2));
        assertEquals(20L, DefaultStorageManager.backoffSeconds(3));
        assertEquals(DefaultStorageManager.BACKOFF_MAX_SECONDS, DefaultStorageManager.backoffSeconds(10));
        assertEquals(DefaultStorageManager.BACKOFF_MAX_SECONDS, DefaultStorageManager.backoffSeconds(Integer.MAX_VALUE));
    }
}
