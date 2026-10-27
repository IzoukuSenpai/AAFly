package com.azurhosts.aafly.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.core.FlightProfileSnapshot;
import com.azurhosts.aafly.storage.DefaultStorageManager;
import com.azurhosts.aafly.storage.SqliteProfileRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Import wFly depuis une base SQLite de test créée ici (SPEC-phase2 A9) : lecture seule, essai à blanc,
 * stratégies max/replace/skip, lignes invalides.
 */
class WFlyImportTest {

    private static final Logger LOG = Logger.getLogger("AAFly-test");
    private static final long MAX = 315_360_000L;
    private static final long NOW = 1_800_000_000L;

    private static final UUID NEW_PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EXISTING_LOW = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EXISTING_HIGH = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COMPACT = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @TempDir
    Path dir;

    private Path plugins;
    private Path wflyDb;
    private ExecutorService io;
    private SqliteProfileRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        // Dossier avec espace : vérifie l'URI file:...?mode=ro.
        plugins = Files.createDirectories(dir.resolve("serveur test").resolve("plugins"));
        Path wfly = Files.createDirectories(plugins.resolve("wFlyV2"));
        wflyDb = wfly.resolve("database.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + wflyDb); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE fly (uniqueId VARCHAR(36), isinFly BOOLEAN, FlyTimeRemaining INTEGER, lastUpdate BIGINT)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO fly VALUES (?, ?, ?, ?)")) {
                Object[][] rows = {
                        {NEW_PLAYER.toString(), 1, 3600, 1L},           // nouveau joueur, en vol
                        {EXISTING_LOW.toString(), 0, 1000, 1L},          // existant à 500 → max : 1000
                        {EXISTING_HIGH.toString(), 1, 100, 1L},          // existant à 9000 → max : inchangé
                        {COMPACT.toString().replace("-", ""), 0, 60, 1L}, // UUID sans tirets accepté
                        {"pas-un-uuid", 0, 60, 1L},                      // invalide
                        {UUID.randomUUID().toString(), 0, -5, 1L},       // temps négatif
                        {UUID.randomUUID().toString(), 0, MAX + 1L, 1L}, // au-delà de limits.max-seconds
                        {UUID.randomUUID().toString(), 0, null, 1L},     // temps absent
                        {NEW_PLAYER.toString(), 0, 99999, 1L},           // doublon : ignoré
                };
                for (Object[] r : rows) {
                    for (int i = 0; i < r.length; i++) {
                        ps.setObject(i + 1, r[i]);
                    }
                    ps.executeUpdate();
                }
            }
            // Texte non numérique (typage dynamique SQLite).
            st.execute("INSERT INTO fly VALUES ('" + UUID.randomUUID() + "', 0, 'beaucoup', 1)");
        }

        io = DefaultStorageManager.newIoExecutor(LOG);
        repo = new SqliteProfileRepository(dir.resolve("aafly").resolve("aafly.db"), io, LOG);
        get(repo.init());
        get(repo.saveAll(List.of(
                new FlightProfileSnapshot(EXISTING_LOW, "Low", 500L, false, 10L, 0L, 0.3D),
                new FlightProfileSnapshot(EXISTING_HIGH, "High", 9000L, false, 10L, 0L, 0.3D))));
    }

    @AfterEach
    void tearDown() throws Exception {
        repo.close();
        io.shutdown();
        assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        return f.get(20, TimeUnit.SECONDS);
    }

    private ImportReport importWFly(MergeStrategy strategy, boolean dryRun) throws Exception {
        List<SourceRow> rows = MigrationSources.readWFlySqlite(plugins, LOG);
        return get(repo.inTransaction("import test", !dryRun,
                c -> ProfileImporter.run(c, rows, strategy, dryRun, MAX, 0.2D, NOW)));
    }

    private Map<UUID, FlightProfileSnapshot> profiles() throws Exception {
        return get(repo.findAll()).stream().collect(Collectors.toMap(FlightProfileSnapshot::uuid, Function.identity()));
    }

    @Test
    void sourceIsReadOnlyAndUnchanged() throws Exception {
        byte[] before = Files.readAllBytes(wflyDb);
        List<SourceRow> rows = MigrationSources.readWFlySqlite(plugins, LOG);
        assertEquals(10, rows.size());
        assertArrayEquals(before, Files.readAllBytes(wflyDb), "la source n'est jamais modifiée");
        assertFalse(Files.exists(wflyDb.resolveSibling("database.db-journal")));
    }

    @Test
    void dryRunWritesNothing() throws Exception {
        Map<UUID, FlightProfileSnapshot> before = profiles();
        ImportReport r = importWFly(MergeStrategy.MAX, true);
        assertTrue(r.dryRun());
        assertEquals(10, r.read());
        assertEquals(3, r.imported()); // NEW_PLAYER, COMPACT (créations) + EXISTING_LOW (mise à jour)
        assertEquals(1, r.updated());
        assertEquals(6, r.ignored()); // 4 invalides + 1 texte + 1 doublon
        assertEquals(1, r.alreadyPresent());
        assertEquals(r.read(), r.imported() + r.ignored() + r.alreadyPresent());
        assertEquals(before, profiles(), "essai à blanc : aucune écriture");
    }

    @Test
    void mergeMaxKeepsTheHigherTime() throws Exception {
        ImportReport r = importWFly(MergeStrategy.MAX, false);
        assertEquals(3, r.imported());
        Map<UUID, FlightProfileSnapshot> p = profiles();
        assertEquals(4, p.size());
        assertEquals(3600L, p.get(NEW_PLAYER).remainingSeconds());
        assertTrue(p.get(NEW_PLAYER).flightEnabled());
        assertEquals(0.2D, p.get(NEW_PLAYER).flySpeed());
        assertEquals(NOW, p.get(NEW_PLAYER).lastUpdateEpochSeconds());
        assertEquals(60L, p.get(COMPACT).remainingSeconds());
        assertEquals(1000L, p.get(EXISTING_LOW).remainingSeconds());
        assertEquals("Low", p.get(EXISTING_LOW).lastKnownName(), "pseudo existant conservé");
        assertEquals(9000L, p.get(EXISTING_HIGH).remainingSeconds());
        assertFalse(p.get(EXISTING_HIGH).flightEnabled());
        assertEquals(java.util.Set.of(NEW_PLAYER, COMPACT, EXISTING_LOW), r.changed());

        // Rejouer l'import ne change plus rien.
        ImportReport again = importWFly(MergeStrategy.MAX, false);
        assertEquals(0, again.imported());
        assertEquals(4, again.alreadyPresent());
    }

    @Test
    void mergeReplaceOverwritesTimeAndFlightState() throws Exception {
        ImportReport r = importWFly(MergeStrategy.REPLACE, false);
        assertEquals(4, r.imported());
        assertEquals(2, r.updated());
        Map<UUID, FlightProfileSnapshot> p = profiles();
        assertEquals(1000L, p.get(EXISTING_LOW).remainingSeconds());
        assertEquals(100L, p.get(EXISTING_HIGH).remainingSeconds());
        assertTrue(p.get(EXISTING_HIGH).flightEnabled());
        assertEquals(0.3D, p.get(EXISTING_HIGH).flySpeed(), "vitesse existante conservée");
    }

    @Test
    void mergeSkipLeavesExistingProfilesUntouched() throws Exception {
        ImportReport r = importWFly(MergeStrategy.SKIP, false);
        assertEquals(2, r.imported());
        assertEquals(0, r.updated());
        assertEquals(2, r.alreadyPresent());
        Map<UUID, FlightProfileSnapshot> p = profiles();
        assertEquals(500L, p.get(EXISTING_LOW).remainingSeconds());
        assertEquals(9000L, p.get(EXISTING_HIGH).remainingSeconds());
        assertEquals(3600L, p.get(NEW_PLAYER).remainingSeconds());
    }

    @Test
    void importBumpsVersionSoCachedWritersMergeInsteadOfOverwriting() throws Exception {
        // Le cache « serveur » a lu EXISTING_LOW (500) avant l'import puis consomme 20 s.
        assertEquals(500L, get(repo.find(EXISTING_LOW)).orElseThrow().remainingSeconds());
        importWFly(MergeStrategy.MAX, false); // 500 → 1000, version incrémentée
        get(repo.upsert(new FlightProfileSnapshot(EXISTING_LOW, "Low", 480L, false, 11L, 0L, 0.3D)));
        assertEquals(980L, profiles().get(EXISTING_LOW).remainingSeconds(), "1000 importés - 20 consommés");
    }

    @Test
    void conflictingConcurrentWriteAbortsTheWholeImport() throws Exception {
        List<SourceRow> rows = MigrationSources.readWFlySqlite(plugins, LOG);
        ExecutionException e = assertThrows(ExecutionException.class, () -> get(repo.inTransaction("import test", true, c -> {
            // Écriture concurrente simulée dans la même transaction, avant les UPDATE de l'import.
            try (PreparedStatement ps = c.prepareStatement("UPDATE aafly_profiles SET version = version + 5")) {
                ps.executeUpdate();
            }
            return ProfileImporter.run(staleVersions(c), rows, MergeStrategy.MAX, false, MAX, 0.2D, NOW);
        })));
        assertTrue(e.getCause().getMessage().contains("conflit"));
        Map<UUID, FlightProfileSnapshot> p = profiles();
        assertEquals(2, p.size(), "rollback : aucune création");
        assertEquals(500L, p.get(EXISTING_LOW).remainingSeconds());
    }

    @Test
    void missingSourceGivesAClearMessage() {
        MigrationException e = assertThrows(MigrationException.class,
                () -> MigrationSources.readWFlySqlite(dir.resolve("vide"), LOG));
        assertTrue(e.getMessage().contains("plugins/wFlyV2/database.db"));
    }

    @Test
    void unexpectedTableIsRejectedWithoutImport() throws Exception {
        Path otherPlugins = Files.createDirectories(dir.resolve("p2"));
        Path db = Files.createDirectories(otherPlugins.resolve("wFlyV2")).resolve("database.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE fly (uuid TEXT, time INTEGER)");
        }
        MigrationException e = assertThrows(MigrationException.class,
                () -> MigrationSources.readWFlySqlite(otherPlugins, LOG));
        assertTrue(e.getMessage().contains("format non reconnu"));
    }

    /**
     * Connexion qui fait voir à l'import les versions d'avant l'écriture concurrente : simule un autre
     * serveur qui écrit entre la lecture et la mise à jour de l'import.
     */
    private static Connection staleVersions(Connection delegate) {
        boolean[] first = {true};
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args != null && args.length == 1
                            && first[0] && ((String) args[0]).startsWith("SELECT uuid, remaining_seconds, flight_enabled, version")) {
                        first[0] = false;
                        return delegate.prepareStatement(((String) args[0]).replace("version FROM", "version - 5 AS version FROM"));
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
