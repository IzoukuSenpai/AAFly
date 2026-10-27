package com.azurhosts.aafly.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.FlightProfileSnapshot;
import com.azurhosts.aafly.testutil.TestServer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC-phase2 A5 / A9 de bout en bout sur {@link DefaultStorageManager} (SQLite réel, serveur Bukkit simulé) :
 * drapeau dirty effacé seulement après succès, backoff, écart distant ré-appliqué en mémoire, relecture après import
 * sans perte des changements locaux, arrêt propre ; et écarts signalés une seule fois par le dépôt.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
class StorageManagerWriteTest {

    private static final Logger LOG = Logger.getLogger("StorageManagerWriteTest");

    @TempDir
    Path dir;

    private TestServer server;
    private DefaultStorageManager storage;

    @BeforeEach
    void setUp() {
        server = TestServer.install();
        Plugin plugin = server.plugin("AAFly", true, dir.toFile());
        storage = new DefaultStorageManager(plugin, Settings.StorageSection.defaults(), () -> 0.1D);
        storage.startSync();
    }

    @AfterEach
    void tearDown() {
        try {
            storage.shutdown();
        } finally {
            server.close();
        }
    }

    private Connection raw() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(SqliteProfileRepository.DEFAULT_FILE_NAME));
    }

    private long dbRemaining(UUID id) throws Exception {
        try (Connection c = raw(); PreparedStatement ps = c.prepareStatement(
                "SELECT remaining_seconds FROM aafly_profiles WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    private void failWrites(boolean fail) throws Exception {
        try (Connection c = raw(); Statement st = c.createStatement()) {
            if (fail) {
                st.execute("CREATE TRIGGER t_fail_upd BEFORE UPDATE ON aafly_profiles"
                        + " BEGIN SELECT RAISE(ABORT, 'panne simulee'); END");
                st.execute("CREATE TRIGGER t_fail_ins BEFORE INSERT ON aafly_profiles"
                        + " BEGIN SELECT RAISE(ABORT, 'panne simulee'); END");
            } else {
                st.execute("DROP TRIGGER t_fail_upd");
                st.execute("DROP TRIGGER t_fail_ins");
            }
        }
    }

    private <T> T await(CompletableFuture<T> f) {
        server.pump(f);
        return f.join();
    }

    private FlightProfile online(UUID id, long seconds) {
        FlightProfile p = await(storage.loadOnline(id, "Pilot"));
        p.setRemainingSeconds(seconds);
        storage.markDirty(p);
        await(storage.flush());
        assertFalse(p.isDirty());
        return p;
    }

    @Test
    void dirtyFlagSurvivesFailedWriteAndClearsOnlyAfterSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        FlightProfile p = online(id, 500L);

        p.setRemainingSeconds(400L);
        storage.markDirty(p);
        failWrites(true);
        CompletableFuture<Void> failed = storage.flush();
        server.pump(failed);
        assertTrue(failed.isCompletedExceptionally());
        assertTrue(p.isDirty(), "le profil reste à écrire après un échec");
        assertEquals(1, storage.consecutiveWriteFailures());
        assertEquals(500L, dbRemaining(id));

        failWrites(false);
        // Modifié pendant l'écriture : le drapeau n'est pas effacé (l'instantané écrit est périmé).
        CompletableFuture<Void> retry = storage.flush();
        p.setRemainingSeconds(390L);
        await(retry);
        assertEquals(0, storage.consecutiveWriteFailures());
        assertEquals(400L, dbRemaining(id));
        assertTrue(p.isDirty());
        await(storage.flush());
        assertFalse(p.isDirty());
        assertEquals(390L, dbRemaining(id));
    }

    @Test
    void remoteWriteIsReappliedInMemory() throws Exception {
        UUID id = UUID.randomUUID();
        FlightProfile p = online(id, 500L);

        // Autre serveur du réseau (même base) : le joueur y a consommé 50 s (500 → 450).
        ExecutorService io2 = DefaultStorageManager.newIoExecutor(LOG);
        SqliteProfileRepository other = new SqliteProfileRepository(
                dir.resolve(SqliteProfileRepository.DEFAULT_FILE_NAME), io2, LOG);
        try {
            other.init().get(10, TimeUnit.SECONDS);
            other.find(id).get(10, TimeUnit.SECONDS);
            other.upsert(new FlightProfileSnapshot(id, "Pilot", 450L, true, 2L, 0L, 0.1D)).get(10, TimeUnit.SECONDS);
        } finally {
            other.close();
            io2.shutdown();
        }

        // Ici, 10 s décomptées (500 → 490) : conflit → 450 - 10 = 440, et la mémoire suit.
        p.setRemainingSeconds(490L);
        storage.markDirty(p);
        await(storage.flush());
        assertEquals(440L, dbRemaining(id));
        assertEquals(440L, p.remainingSeconds(), "écart distant ré-appliqué en mémoire");
        assertFalse(p.isDirty());

        // Écriture suivante : aucun double comptage.
        p.setRemainingSeconds(430L);
        storage.markDirty(p);
        await(storage.flush());
        assertEquals(430L, dbRemaining(id));
        assertEquals(430L, p.remainingSeconds());
    }

    @Test
    void reloadAfterImportKeepsLocalChangesMadeDuringReload() throws Exception {
        UUID id = UUID.randomUUID();
        FlightProfile p = online(id, 300L);
        // Import externe : la ligne passe à 1000 s.
        try (Connection c = raw(); PreparedStatement ps = c.prepareStatement(
                "UPDATE aafly_profiles SET remaining_seconds = 1000, version = version + 1 WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
        CompletableFuture<Integer> reload = storage.reloadAfterImport(Set.of(id));
        // Pendant la relecture : 5 s décomptées et un crédit de 60 s (jeton, AAItems…).
        p.setRemainingSeconds(p.remainingSeconds() - 5L + 60L);
        storage.markDirty(p);
        assertEquals(1, await(reload));
        assertEquals(1055L, p.remainingSeconds(), "import + changements locaux, rien n'est perdu");
        await(storage.flush());
        assertEquals(1055L, dbRemaining(id));
    }

    @Test
    void reloadAfterImportSkipsRefreshWhenPriorWriteFails() throws Exception {
        UUID id = UUID.randomUUID();
        FlightProfile p = online(id, 300L);
        try (Connection c = raw(); PreparedStatement ps = c.prepareStatement(
                "UPDATE aafly_profiles SET remaining_seconds = 1000, version = version + 1 WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
        p.setRemainingSeconds(250L); // modification locale non écrite
        storage.markDirty(p);
        failWrites(true);
        assertEquals(0, await(storage.reloadAfterImport(Set.of(id))));
        assertEquals(250L, p.remainingSeconds(), "la relecture n'écrase pas une modification non écrite");
        assertTrue(p.isDirty());
        failWrites(false);
        await(storage.flush());
        // La prochaine écriture versionnée fusionne : 1000 + (250 - 300).
        assertEquals(950L, dbRemaining(id));
    }

    @Test
    void repositoryReportsRemoteDeltaOnce() throws Exception {
        storage.shutdown(); // dépôts manipulés directement
        UUID id = UUID.randomUUID();
        Path db = dir.resolve("deltas.db");
        ExecutorService ioA = DefaultStorageManager.newIoExecutor(LOG);
        ExecutorService ioB = DefaultStorageManager.newIoExecutor(LOG);
        SqliteProfileRepository a = new SqliteProfileRepository(db, ioA, LOG);
        SqliteProfileRepository b = new SqliteProfileRepository(db, ioB, LOG);
        try {
            a.init().get(10, TimeUnit.SECONDS);
            b.init().get(10, TimeUnit.SECONDS);
            a.upsert(new FlightProfileSnapshot(id, "Pilot", 100L, true, 1L, 0L, 0.1D)).get(10, TimeUnit.SECONDS);
            assertEquals(100L, b.find(id).get(10, TimeUnit.SECONDS).orElseThrow().remainingSeconds());
            // A consomme 10 s ; B ajoute 50 s depuis sa base périmée (100) → 90 + 50 = 140, écart -10 pour B.
            a.upsert(new FlightProfileSnapshot(id, "Pilot", 90L, true, 2L, 0L, 0.1D)).get(10, TimeUnit.SECONDS);
            var first = b.saveDetailed(List.of(new FlightProfileSnapshot(id, "Pilot", 150L, true, 3L, 0L, 0.1D)))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(Map.of(id, -10L), first.remoteDeltas());
            // Écriture en vol avant l'absorption : l'écart n'est pas signalé une seconde fois.
            var second = b.saveDetailed(List.of(new FlightProfileSnapshot(id, "Pilot", 150L, true, 4L, 0L, 0.1D)))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(Map.of(), second.remoteDeltas());
            b.absorbRemoteDelta(id, -10L); // mémoire de B : 150 → 140
            var third = b.saveDetailed(List.of(new FlightProfileSnapshot(id, "Pilot", 135L, true, 5L, 0L, 0.1D)))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(Map.of(), third.remoteDeltas());
            assertEquals(135L, a.findAll().get(10, TimeUnit.SECONDS).getFirst().remainingSeconds());
        } finally {
            a.close();
            b.close();
            ioA.shutdown();
            ioB.shutdown();
        }
    }
}
