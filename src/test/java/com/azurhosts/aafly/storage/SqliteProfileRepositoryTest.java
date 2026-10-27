package com.azurhosts.aafly.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.core.FlightProfileSnapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteProfileRepositoryTest {

    private static final Logger LOG = Logger.getLogger("AAFly-test");

    @TempDir
    Path dir;

    private ExecutorService io;
    private SqliteProfileRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        io = DefaultStorageManager.newIoExecutor(LOG);
        repo = new SqliteProfileRepository(dir.resolve("sub").resolve("aafly.db"), io, LOG);
        get(repo.init());
    }

    @AfterEach
    void tearDown() throws Exception {
        repo.close();
        io.shutdown();
        assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    private static FlightProfileSnapshot snap(UUID id, String name, long remaining, long lastUpdate) {
        return new FlightProfileSnapshot(id, name, remaining, true, lastUpdate, 42L, 0.3D);
    }

    @Test
    void upsertThenFindRoundTrip() throws Exception {
        UUID id = UUID.randomUUID();
        assertEquals(Optional.empty(), get(repo.find(id)));

        get(repo.upsert(snap(id, "Dylan", 120L, 1000L)));
        assertEquals(Optional.of(snap(id, "Dylan", 120L, 1000L)), get(repo.find(id)));

        // second upsert : mise à jour, pas de doublon
        FlightProfileSnapshot updated = new FlightProfileSnapshot(id, "Dylan_2", 5L, false, 2000L, 0L, 1.0D);
        get(repo.upsert(updated));
        assertEquals(Optional.of(updated), get(repo.find(id)));
        assertEquals(1, get(repo.findAll()).size());
    }

    @Test
    void findByNameIsCaseInsensitiveAndPrefersMostRecent() throws Exception {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        get(repo.upsert(snap(older, "Steve", 1L, 100L)));
        get(repo.upsert(snap(newer, "STEVE", 2L, 200L)));

        Optional<FlightProfileSnapshot> found = get(repo.findByName("steve"));
        assertTrue(found.isPresent());
        assertEquals(newer, found.get().uuid());
        assertEquals(Optional.empty(), get(repo.findByName("Alex")));
    }

    @Test
    void findByNameRejectsInvalidNamesWithoutQuerying() throws Exception {
        get(repo.upsert(snap(UUID.randomUUID(), "Bob", 1L, 1L)));
        assertEquals(Optional.empty(), get(repo.findByName("' OR '1'='1")));
        assertEquals(Optional.empty(), get(repo.findByName("%")));
        assertEquals(Optional.empty(), get(repo.findByName("")));
    }

    @Test
    void valuesWithSqlMetacharactersAreBoundNotInterpreted() throws Exception {
        // Le pseudo invalide est neutralisé par le snapshot ; on vérifie qu'aucune table n'est touchée.
        UUID id = UUID.randomUUID();
        get(repo.upsert(new FlightProfileSnapshot(id, "x'); DROP TABLE aafly_profiles;--", 1L, false, 1L, 0L, 0.1D)));
        assertTrue(get(repo.find(id)).isPresent());
    }

    @Test
    void saveAllWritesEveryProfileInOneTransaction() throws Exception {
        List<FlightProfileSnapshot> batch = new ArrayList<>();
        for (int i = 0; i < AbstractSqlProfileRepository.BATCH_SIZE + 37; i++) {
            batch.add(snap(UUID.randomUUID(), "p" + i, i, i));
        }
        get(repo.saveAll(batch));
        assertEquals(batch.size(), get(repo.findAll()).size());
        get(repo.saveAll(List.of())); // vide : no-op
    }

    @Test
    void outOfRangeValuesAreClampedOnRead() throws Exception {
        UUID id = UUID.randomUUID();
        get(repo.upsert(snap(id, "Clamp", 1L, 1L)));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + repo.file());
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE aafly_profiles SET remaining_seconds = ?, fly_speed = ? WHERE uuid = ?")) {
            ps.setLong(1, -50L);
            ps.setDouble(2, 99.0D);
            ps.setString(3, id.toString());
            assertEquals(1, ps.executeUpdate());
        }
        FlightProfileSnapshot s = get(repo.find(id)).orElseThrow();
        assertEquals(0L, s.remainingSeconds());
        assertEquals(1.0D, s.flySpeed());
    }

    @Test
    void tokenCanBeRedeemedOnlyOnce() throws Exception {
        UUID token = UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        get(repo.insertToken(new TokenRecord(token, 300L, creator, 10L)));

        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        assertTrue(get(repo.redeemToken(token, 300L, alice, 20L)));
        assertFalse(get(repo.redeemToken(token, 300L, alice, 21L)), "double encaissement refusé");
        assertFalse(get(repo.redeemToken(token, 300L, bob, 22L)), "encaissement par un autre refusé");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + repo.file());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT redeemed_by, redeemed_at FROM aafly_tokens WHERE token_id = ?")) {
            ps.setString(1, token.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(alice.toString(), rs.getString(1));
                assertEquals(20L, rs.getLong(2));
            }
        }
    }

    @Test
    void concurrentRedeemsYieldExactlyOneSuccess() throws Exception {
        UUID token = UUID.randomUUID();
        get(repo.insertToken(new TokenRecord(token, 60L, null, 1L)));
        List<CompletableFuture<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            attempts.add(repo.redeemToken(token, 60L, UUID.randomUUID(), 2L));
        }
        int successes = 0;
        for (CompletableFuture<Boolean> f : attempts) {
            if (get(f)) {
                successes++;
            }
        }
        assertEquals(1, successes);
    }

    @Test
    void redeemWithWrongAmountOrUnknownTokenFails() throws Exception {
        UUID token = UUID.randomUUID();
        get(repo.insertToken(new TokenRecord(token, 60L, null, 1L)));
        assertFalse(get(repo.redeemToken(token, 3600L, UUID.randomUUID(), 2L)), "montant falsifié");
        assertFalse(get(repo.redeemToken(UUID.randomUUID(), 60L, UUID.randomUUID(), 2L)), "jeton inconnu");
        assertFalse(get(repo.redeemToken(token, 0L, UUID.randomUUID(), 2L)), "montant nul");
        assertTrue(get(repo.redeemToken(token, 60L, UUID.randomUUID(), 3L)), "le jeton reste encaissable");
    }

    @Test
    void duplicateTokenIdIsRejected() throws Exception {
        UUID token = UUID.randomUUID();
        get(repo.insertToken(new TokenRecord(token, 60L, null, 1L)));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> get(repo.insertToken(new TokenRecord(token, 9999L, null, 1L))));
        assertInstanceOf(StorageException.class, e.getCause());
    }

    @Test
    void initIsIdempotentAndRecordsSchemaVersion() throws Exception {
        get(repo.init());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + repo.file())) {
            assertEquals(SchemaMigrator.LATEST_VERSION, SchemaMigrator.currentVersion(c));
        }
        // un second dépôt sur le même fichier ne refait pas la migration et lit les mêmes données
        UUID id = UUID.randomUUID();
        get(repo.upsert(snap(id, "Keep", 7L, 7L)));
        repo.close();
        repo = new SqliteProfileRepository(repo.file(), io, LOG);
        get(repo.init());
        assertEquals(7L, get(repo.find(id)).orElseThrow().remainingSeconds());
    }

    @Test
    void newerSchemaIsRefused() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + repo.file());
             PreparedStatement ps = c.prepareStatement("INSERT INTO aafly_schema (version) VALUES (?)")) {
            ps.setInt(1, SchemaMigrator.LATEST_VERSION + 1);
            ps.executeUpdate();
        }
        repo.close();
        repo = new SqliteProfileRepository(repo.file(), io, LOG);
        ExecutionException e = assertThrows(ExecutionException.class, () -> get(repo.init()));
        assertInstanceOf(StorageException.class, e.getCause());
    }

    @Test
    void operationsFailCleanlyAfterClose() throws Exception {
        repo.close();
        ExecutionException e = assertThrows(ExecutionException.class, () -> get(repo.find(UUID.randomUUID())));
        assertInstanceOf(StorageException.class, e.getCause());
    }

    @Test
    void operationsFailCleanlyAfterExecutorShutdown() throws Exception {
        io.shutdown();
        ExecutionException e = assertThrows(ExecutionException.class, () -> get(repo.find(UUID.randomUUID())));
        assertInstanceOf(StorageException.class, e.getCause());
    }

    @Test
    void saveWithTokenPersistsDebitAndTokenAtomically() throws Exception {
        UUID player = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        get(repo.upsert(snap(player, "Dylan", 500L, 10L)));
        get(repo.saveWithToken(snap(player, "Dylan", 200L, 11L), new TokenRecord(token, 300L, player, 11L)));
        assertEquals(200L, get(repo.find(player)).orElseThrow().remainingSeconds());
        assertTrue(get(repo.redeemToken(token, 300L, player, 12L)));

        // Jeton déjà existant : la transaction échoue et le débit n'est pas écrit.
        assertThrows(ExecutionException.class, () -> get(repo.saveWithToken(snap(player, "Dylan", 50L, 13L),
                new TokenRecord(token, 300L, player, 13L))));
        assertEquals(200L, get(repo.find(player)).orElseThrow().remainingSeconds(), "rollback du débit");
    }
}
