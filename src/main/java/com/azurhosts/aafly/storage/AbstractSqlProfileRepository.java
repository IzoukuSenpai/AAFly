package com.azurhosts.aafly.storage;

import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.FlightProfileSnapshot;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Socle commun des dépôts SQL (SQLite, MySQL) : pool HikariCP, requêtes préparées, transactions,
 * verrou optimiste (SPEC-phase2 A5).
 *
 * <p><b>Threads :</b> chaque opération est soumise à l'executor I/O fourni (un seul thread
 * {@code AAFly-IO} : l'ordre des écritures et des lectures est donc celui des appels). Le pool est créé
 * dans {@link #init()}, sur ce thread, pour ne jamais bloquer le main thread sur une connexion réseau.
 * Aucun appel à l'API Bukkit ici.
 *
 * <p><b>Verrou optimiste :</b> chaque ligne {@code aafly_profiles} porte une colonne {@code version}
 * incrémentée à chaque écriture. Le dépôt mémorise, par joueur, la <i>base</i> de la dernière
 * synchronisation (version lue/écrite, temps en base, temps en mémoire à cet instant). Une écriture
 * est un {@code UPDATE ... WHERE uuid = ? AND version = ?} ; si un autre serveur (ou un import) a écrit
 * entre-temps, la ligne est relue (lecture verrouillante en MySQL), le <b>delta</b> local
 * ({@code mémoire - base}) est ré-appliqué sur la valeur relue, puis l'écriture est retentée
 * ({@value #MAX_WRITE_ATTEMPTS} tentatives au total, puis WARN : la ligne n'est pas écrite et reste à
 * écrire côté cache). Les autres champs (vol voulu, vitesse, recharge, pseudo) suivent la règle
 * « dernier écrivain gagne ». La base n'est mise à jour qu'après un commit réussi.
 *
 * <p>La base d'un joueur est posée à la première lecture (jamais écrasée par une lecture ultérieure,
 * l'instance en mémoire restant celle de la première lecture) et oubliée par {@link #release(UUID)}
 * quand le cache abandonne l'instance.
 *
 * <p><b>Sécurité :</b> uniquement des {@link PreparedStatement} à paramètres liés, noms de tables et de
 * colonnes constants, aucune valeur concaténée dans le SQL ; le mot de passe n'apparaît dans aucun log.
 */
public abstract sealed class AbstractSqlProfileRepository implements ProfileRepository
        permits SqliteProfileRepository, MySqlProfileRepository {

    /** Nombre maximal de lignes par lot JDBC (jetons, import). */
    static final int BATCH_SIZE = 500;
    /** Tentatives d'écriture d'une ligne en cas de conflit de version (SPEC-phase2 A5). */
    public static final int MAX_WRITE_ATTEMPTS = 3;
    /** Version « ligne absente » d'une base. */
    static final long ABSENT = -1L;
    /** Clé de {@code aafly_server} : heure d'arrêt propre (epoch, secondes). */
    public static final String KEY_SHUTDOWN_AT = "shutdown_at";
    /** Motif d'une clé {@code aafly_server}. */
    private static final Pattern SERVER_KEY = Pattern.compile("^[a-z0-9_.\\-]{1,64}$");

    /** Colonnes de {@code aafly_profiles}, dans l'ordre des paramètres d'insertion. */
    protected static final String PROFILE_COLUMNS =
            "uuid, name, remaining_seconds, flight_enabled, last_update, cooldown_until, fly_speed";
    /** Colonnes lues (profil + version). */
    protected static final String SELECT_COLUMNS = PROFILE_COLUMNS + ", version";

    private static final String FIND_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM aafly_profiles WHERE uuid = ?";
    private static final String FIND_ALL_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM aafly_profiles";
    private static final String UPDATE_IF_VERSION_SQL =
            "UPDATE aafly_profiles SET name = ?, remaining_seconds = ?, flight_enabled = ?, last_update = ?,"
                    + " cooldown_until = ?, fly_speed = ?, version = version + 1"
                    + " WHERE uuid = ? AND version = ?";
    private static final String INSERT_TOKEN_SQL =
            "INSERT INTO aafly_tokens (token_id, seconds, created_by, created_at, redeemed_by, redeemed_at)"
                    + " VALUES (?, ?, ?, ?, NULL, NULL)";
    private static final String REDEEM_TOKEN_SQL =
            "UPDATE aafly_tokens SET redeemed_by = ?, redeemed_at = ?"
                    + " WHERE token_id = ? AND seconds = ? AND redeemed_at IS NULL";

    /**
     * Base de synchronisation d'un joueur.
     *
     * <p>{@code dbRemaining - localRemaining} est l'<i>écart distant</i> pas encore ré-appliqué en mémoire
     * (écritures d'un autre serveur constatées lors d'un conflit). {@code reported} est la part de cet écart déjà
     * signalée au gestionnaire de stockage ({@link WriteReport#remoteDeltas()}), qui la ré-applique en mémoire
     * puis appelle {@link #absorbRemoteDelta(UUID, long)} : un même écart n'est jamais signalé deux fois.
     *
     * @param version         version de la ligne ({@link #ABSENT} si la ligne n'existait pas)
     * @param dbRemaining     temps en base à la synchronisation
     * @param localRemaining  temps en mémoire à la synchronisation (coordonnées de la mémoire)
     * @param reported        part de l'écart distant déjà signalée et non encore absorbée
     */
    record Base(long version, long dbRemaining, long localRemaining, long reported) {

        /**
         * Base sans écart signalé.
         *
         * @param version        version de la ligne
         * @param dbRemaining    temps en base
         * @param localRemaining temps en mémoire
         */
        Base(long version, long dbRemaining, long localRemaining) {
            this(version, dbRemaining, localRemaining, 0L);
        }
    }

    /**
     * Résultat d'une écriture groupée (SPEC-phase2 A5).
     *
     * @param unwritten    joueurs non écrits (conflit persistant)
     * @param remoteDeltas pour chaque joueur écrit dont la valeur en base diffère de la mémoire à cause
     *                     d'écritures distantes : écart à ré-appliquer en mémoire (puis à confirmer par
     *                     {@link #absorbRemoteDelta(UUID, long)}) ; absent si nul
     */
    public record WriteReport(Set<UUID> unwritten, Map<UUID, Long> remoteDeltas) {

        /**
         * Copies défensives.
         *
         * @param unwritten    joueurs non écrits
         * @param remoteDeltas écarts distants
         */
        public WriteReport {
            unwritten = Set.copyOf(unwritten);
            remoteDeltas = Map.copyOf(remoteDeltas);
        }
    }

    /** Executor I/O ({@code AAFly-IO}). */
    protected final Executor io;
    /** Journal du plugin. */
    protected final Logger logger;

    /**
     * Bases par joueur. Lue et écrite uniquement depuis les tâches I/O ; {@link ConcurrentHashMap}
     * car l'executor fourni n'est pas garanti mono-thread (SPEC-phase2 A5 : pas de HashMap partagée).
     */
    private final Map<UUID, Base> bases = new ConcurrentHashMap<>();

    private volatile @Nullable HikariDataSource dataSource;
    private volatile boolean closed;

    /**
     * @param io     executor I/O dédié (mono-thread recommandé : garantit l'ordre des opérations)
     * @param logger journal du plugin
     */
    protected AbstractSqlProfileRepository(Executor io, Logger logger) {
        this.io = Objects.requireNonNull(io, "io");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ------------------------------------------------------------------ spécifique au moteur

    /**
     * Construit la configuration du pool (appelé une seule fois, sur le thread I/O).
     *
     * @return configuration HikariCP (le mot de passe n'est jamais loggé)
     * @throws SQLException si la préparation échoue
     */
    protected abstract HikariConfig createPoolConfig() throws SQLException;

    /**
     * @return ordres DDL propres au moteur
     */
    protected abstract SchemaMigrator.Dialect dialect();

    /**
     * @return recherche insensible à la casse par pseudo ({@link #SELECT_COLUMNS}), 1 paramètre, le plus
     *         récent d'abord, 1 ligne max
     */
    protected abstract String findByNameSql();

    /**
     * @return insertion « si absente » d'un profil, 7 paramètres ({@link #PROFILE_COLUMNS}), version 1 ;
     *         0 ligne modifiée si la ligne existe déjà
     */
    protected abstract String insertIfAbsentSql();

    /**
     * @return lecture de {@code remaining_seconds, version} par uuid (1 paramètre), verrouillante si le moteur
     *         le permet (MySQL {@code FOR UPDATE})
     */
    protected abstract String lockingReadSql();

    /**
     * @return upsert dans {@code aafly_server}, 2 paramètres (clé, valeur)
     */
    protected abstract String serverUpsertSql();

    /**
     * @return lecture d'une valeur de {@code aafly_server}, 1 paramètre (clé)
     */
    protected abstract String serverSelectSql();

    /**
     * @return description du stockage pour les logs (sans secret)
     */
    public abstract String describe();

    /**
     * Préparation éventuelle avant la création du pool (ex. dossier du fichier SQLite), thread I/O.
     *
     * @throws SQLException si la préparation échoue
     */
    protected void beforePoolCreation() throws SQLException {
        // rien par défaut
    }

    // ------------------------------------------------------------------ ProfileRepository

    @Override
    public CompletableFuture<Void> init() {
        return run("initialisation du schéma", () -> {
            if (closed) {
                throw new SQLException("dépôt fermé");
            }
            HikariDataSource ds = dataSource;
            if (ds == null) {
                beforePoolCreation();
                try {
                    ds = new HikariDataSource(createPoolConfig());
                } catch (RuntimeException e) {
                    // PoolInitializationException & co : le mot de passe n'est pas dans l'URL, donc pas ici.
                    throw new SQLException("connexion à la base impossible (" + describe() + ") : "
                            + rootMessage(e), e);
                }
                dataSource = ds;
            }
            try (Connection c = ds.getConnection()) {
                new SchemaMigrator(dialect(), logger).migrate(c);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<FlightProfileSnapshot>> find(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return supply("lecture du profil " + uuid, () -> {
            try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(FIND_SQL)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.ofNullable(readAndTrack(rs, false));
                    }
                }
            }
            bases.putIfAbsent(uuid, new Base(ABSENT, 0L, 0L));
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Optional<FlightProfileSnapshot>> findByName(String name) {
        if (name == null || !FlightProfile.NAME_PATTERN.matcher(name).matches()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return supply("recherche du profil de " + name, () -> {
            try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(findByNameSql())) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(readAndTrack(rs, false)) : Optional.empty();
                }
            }
        });
    }

    /**
     * Relit un profil en <b>remplaçant</b> sa base (après un import externe) : l'appelant doit appliquer
     * la valeur lue à l'instance en mémoire et ne soumettre aucune écriture de ce joueur entre l'appel et
     * cette application.
     *
     * @param uuid joueur
     * @return profil lu, ou vide
     */
    public CompletableFuture<Optional<FlightProfileSnapshot>> refresh(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return supply("relecture du profil " + uuid, () -> {
            try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(FIND_SQL)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.ofNullable(readAndTrack(rs, true));
                    }
                }
            }
            bases.put(uuid, new Base(ABSENT, 0L, 0L));
            return Optional.empty();
        });
    }

    /**
     * Oublie la base d'un joueur (l'instance a quitté la mémoire ; la prochaine lecture repartira de la base).
     * Soumis à l'executor I/O, donc ordonné après les écritures déjà en file.
     *
     * @param uuid joueur
     */
    public void release(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        try {
            io.execute(() -> bases.remove(uuid));
        } catch (RejectedExecutionException e) {
            bases.remove(uuid); // executor arrêté : plus aucune écriture ne suivra
        }
    }

    /**
     * @return nombre de bases mémorisées (diagnostic / tests)
     */
    public int trackedCount() {
        return bases.size();
    }

    @Override
    public CompletableFuture<Void> upsert(FlightProfileSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return saveReporting(List.of(snapshot)).thenApply(unwritten -> {
            if (!unwritten.isEmpty()) {
                throw new StorageException("Conflit d'écriture persistant pour le profil " + snapshot.uuid()
                        + " (nouvel essai au prochain flush).");
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<FlightProfileSnapshot> snapshots) {
        return saveReporting(snapshots).thenApply(unwritten -> {
            if (!unwritten.isEmpty()) {
                throw new StorageException(unwritten.size()
                        + " profil(s) non écrit(s) après conflits de version répétés.");
            }
            return null;
        });
    }

    /**
     * Écriture groupée dans une transaction, avec verrou optimiste ligne par ligne. Les lignes écrites
     * sont validées même si d'autres sont en conflit persistant.
     *
     * @param snapshots profils (un seul instantané par joueur ; en cas de doublon, le dernier gagne)
     * @return future des joueurs <b>non</b> écrits (conflit persistant), complétée après commit
     */
    public CompletableFuture<Set<UUID>> saveReporting(Collection<FlightProfileSnapshot> snapshots) {
        return saveDetailed(snapshots).thenApply(WriteReport::unwritten);
    }

    /**
     * Comme {@link #saveReporting(Collection)}, en signalant aussi les écarts distants constatés (SPEC-phase2 A5 :
     * « ré-appliquer le delta en mémoire »). L'appelant ré-applique chaque écart à l'instance en mémoire sur le
     * main thread puis appelle {@link #absorbRemoteDelta(UUID, long)} ; s'il ne le fait pas, l'écart reste
     * conservé en base (fusionné aux écritures suivantes) mais n'est plus signalé.
     *
     * @param snapshots profils (un seul instantané par joueur ; en cas de doublon, le dernier gagne)
     * @return future du rapport, complétée après commit
     */
    public CompletableFuture<WriteReport> saveDetailed(Collection<FlightProfileSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        Map<UUID, FlightProfileSnapshot> byUuid = new java.util.LinkedHashMap<>();
        for (FlightProfileSnapshot s : snapshots) {
            byUuid.put(Objects.requireNonNull(s, "snapshot").uuid(), s);
        }
        if (byUuid.isEmpty()) {
            return CompletableFuture.completedFuture(new WriteReport(Set.of(), Map.of()));
        }
        List<FlightProfileSnapshot> copy = List.copyOf(byUuid.values());
        return supply("sauvegarde de " + copy.size() + " profil(s)", () -> {
            Set<UUID> unwritten = new LinkedHashSet<>();
            Map<UUID, Base> pending = new HashMap<>();
            Map<UUID, Long> deltas = new HashMap<>();
            try (Connection c = connection()) {
                boolean previousAutoCommit = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    for (FlightProfileSnapshot s : copy) {
                        if (!writeVersioned(c, s, pending, deltas)) {
                            unwritten.add(s.uuid());
                        }
                    }
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    rollbackQuietly(c, e);
                    throw e;
                } finally {
                    c.setAutoCommit(previousAutoCommit);
                }
            }
            bases.putAll(pending); // après commit uniquement
            return new WriteReport(unwritten, deltas);
        });
    }

    /**
     * Confirme qu'un écart distant signalé par {@link #saveDetailed(Collection)} a été ré-appliqué en mémoire :
     * la base passe dans les nouvelles coordonnées de la mémoire ({@code localRemaining += delta}) et l'écart
     * n'est plus en attente. À appeler sur le main thread <b>au moment même</b> où la mémoire est modifiée :
     * la tâche passe en file sur {@code AAFly-IO}, donc après les écritures dont l'instantané précède la
     * modification et avant celles qui la suivent. Sans effet si la base a été oubliée ou remplacée entre-temps.
     *
     * @param uuid  joueur
     * @param delta écart signalé (tel que reçu, même si la mémoire a été bornée)
     */
    public void absorbRemoteDelta(UUID uuid, long delta) {
        Objects.requireNonNull(uuid, "uuid");
        if (delta == 0L) {
            return;
        }
        Runnable shift = () -> bases.computeIfPresent(uuid, (k, b) -> new Base(b.version(), b.dbRemaining(),
                saturatedAdd(b.localRemaining(), delta), saturatedAdd(b.reported(), -delta)));
        try {
            io.execute(shift);
        } catch (RejectedExecutionException e) {
            // Executor arrêté : plus aucune écriture ne suivra.
        }
    }

    @Override
    public CompletableFuture<Void> insertToken(TokenRecord token) {
        Objects.requireNonNull(token, "token");
        return run("création du jeton " + token.tokenId(), () -> {
            try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(INSERT_TOKEN_SQL)) {
                executeInsertToken(ps, token);
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveWithToken(FlightProfileSnapshot debited, TokenRecord token) {
        Objects.requireNonNull(debited, "debited");
        Objects.requireNonNull(token, "token");
        return run("débit et création du jeton " + token.tokenId(), () -> {
            Map<UUID, Base> pending = new HashMap<>();
            try (Connection c = connection()) {
                boolean previousAutoCommit = c.getAutoCommit();
                c.setAutoCommit(false);
                try (PreparedStatement insert = c.prepareStatement(INSERT_TOKEN_SQL)) {
                    // Écart distant non signalé ici (reported inchangé) : il le sera à la prochaine écriture.
                    if (!writeVersioned(c, debited, pending, null)) {
                        throw new SQLException("conflit d'écriture persistant sur le profil " + debited.uuid());
                    }
                    executeInsertToken(insert, token);
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    rollbackQuietly(c, e);
                    throw e;
                } finally {
                    c.setAutoCommit(previousAutoCommit);
                }
            }
            bases.putAll(pending);
        });
    }

    private static void executeInsertToken(PreparedStatement ps, TokenRecord token) throws SQLException {
        ps.setString(1, token.tokenId().toString());
        ps.setLong(2, token.seconds());
        UUID createdBy = token.createdBy();
        if (createdBy != null) {
            ps.setString(3, createdBy.toString());
        } else {
            ps.setNull(3, Types.CHAR);
        }
        ps.setLong(4, token.createdAtEpochSeconds());
        if (ps.executeUpdate() != 1) {
            throw new SQLException("insertion du jeton sans effet");
        }
    }

    @Override
    public CompletableFuture<Boolean> redeemToken(UUID tokenId, long seconds, UUID redeemer,
                                                  long redeemedAtEpochSeconds) {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(redeemer, "redeemer");
        if (seconds <= 0L) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        return supply("encaissement du jeton " + tokenId, () -> {
            try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(REDEEM_TOKEN_SQL)) {
                ps.setString(1, redeemer.toString());
                ps.setLong(2, redeemedAtEpochSeconds);
                ps.setString(3, tokenId.toString());
                ps.setLong(4, seconds);
                // Condition atomique côté base : seul le premier UPDATE voit encore redeemed_at IS NULL.
                return ps.executeUpdate() == 1;
            }
        });
    }

    // ------------------------------------------------------------------ aafly_server

    /**
     * Écrit une valeur de {@code aafly_server} (ex. {@link #KEY_SHUTDOWN_AT}).
     *
     * @param key   clé {@code ^[a-z0-9_.-]{1,64}$}
     * @param value valeur
     * @return future complétée après écriture
     */
    public CompletableFuture<Void> putServerValue(String key, long value) {
        String k = checkServerKey(key);
        return run("écriture de " + k, () -> {
            try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(serverUpsertSql())) {
                ps.setString(1, k);
                ps.setLong(2, value);
                ps.executeUpdate();
            }
        });
    }

    /**
     * @param key clé {@code ^[a-z0-9_.-]{1,64}$}
     * @return valeur de {@code aafly_server}, ou vide
     */
    public CompletableFuture<OptionalLong> serverValue(String key) {
        String k = checkServerKey(key);
        return supply("lecture de " + k, () -> {
            try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(serverSelectSql())) {
                ps.setString(1, k);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long v = rs.getLong(1);
                        return rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(v);
                    }
                    return OptionalLong.empty();
                }
            }
        });
    }

    private static String checkServerKey(String key) {
        if (key == null || !SERVER_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("clé aafly_server invalide");
        }
        return key;
    }

    // ------------------------------------------------------------------ transactions externes

    /**
     * Corps d'une transaction.
     *
     * @param <T> résultat
     */
    @FunctionalInterface
    public interface TransactionBody<T> {
        /**
         * @param connection connexion en auto-commit désactivé
         * @return résultat
         * @throws SQLException erreur SQL (provoque un rollback)
         */
        T apply(Connection connection) throws SQLException;
    }

    /**
     * Exécute {@code body} dans une transaction sur l'executor I/O (ordonnée avec les autres écritures).
     * Commit si {@code commit} est vrai et que le corps réussit, rollback sinon. Utilisé par l'import
     * (package {@code migration}) ; les requêtes du corps doivent être préparées et paramétrées.
     *
     * @param operation libellé (logs, sans secret)
     * @param commit    {@code false} pour un essai à blanc (rollback systématique)
     * @param body      corps
     * @param <T>       résultat
     * @return future du résultat, complétée sur le thread I/O
     */
    public <T> CompletableFuture<T> inTransaction(String operation, boolean commit, TransactionBody<T> body) {
        Objects.requireNonNull(body, "body");
        return supply(operation, () -> {
            try (Connection c = connection()) {
                boolean previousAutoCommit = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    T result = body.apply(c);
                    if (commit) {
                        c.commit();
                    } else {
                        c.rollback();
                    }
                    return result;
                } catch (SQLException | RuntimeException e) {
                    rollbackQuietly(c, e);
                    throw e;
                } finally {
                    c.setAutoCommit(previousAutoCommit);
                }
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        HikariDataSource ds = dataSource;
        dataSource = null;
        if (ds != null) {
            try {
                ds.close();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Fermeture du pool de connexions en erreur", e);
            }
        }
        bases.clear();
    }

    /**
     * @return {@code true} après {@link #close()}
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Lit tous les profils valides (diagnostic / tests ; thread I/O). Ne pose aucune base.
     *
     * @return future des profils
     */
    public CompletableFuture<List<FlightProfileSnapshot>> findAll() {
        return supply("lecture de tous les profils", () -> {
            List<FlightProfileSnapshot> out = new ArrayList<>();
            try (Connection c = connection();
                 PreparedStatement ps = c.prepareStatement(FIND_ALL_SQL);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    FlightProfileSnapshot s = readSnapshot(rs);
                    if (s != null) {
                        out.add(s);
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ verrou optimiste

    /**
     * Écrit une ligne avec verrou optimiste (thread I/O, dans la transaction de l'appelant).
     *
     * @param c       connexion (auto-commit désactivé)
     * @param s       instantané à écrire
     * @param pending bases à publier après commit (mise à jour en cas de succès)
     * @param deltas  si non nul, reçoit l'écart distant à ré-appliquer en mémoire (non nul uniquement) ; si nul,
     *                l'écart n'est pas signalé et le reste à la prochaine écriture
     * @return {@code true} si la ligne est écrite, {@code false} après {@value #MAX_WRITE_ATTEMPTS} conflits
     * @throws SQLException erreur SQL
     */
    private boolean writeVersioned(Connection c, FlightProfileSnapshot s, Map<UUID, Base> pending,
                                   @Nullable Map<UUID, Long> deltas) throws SQLException {
        UUID uuid = s.uuid();
        Base base = pending.containsKey(uuid) ? pending.get(uuid) : bases.get(uuid);
        if (base == null) {
            // Base inconnue (instance jamais lue par ce dépôt) : dernier écrivain gagne sur la valeur absolue.
            Current cur = lockingRead(c, uuid);
            base = new Base(cur == null ? ABSENT : cur.version(), s.remainingSeconds(), s.remainingSeconds());
        }
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            long value = merge(base, s.remainingSeconds());
            int rows = base.version() == ABSENT ? insertIfAbsent(c, s, value) : updateIfVersion(c, s, value, base.version());
            if (rows == 1) {
                long newVersion = base.version() == ABSENT ? 1L : base.version() + 1L;
                // Écart entre la base et la mémoire après cette écriture (écritures distantes fusionnées).
                long gap = saturatedAdd(value, -s.remainingSeconds());
                long reported = base.reported();
                if (deltas != null) {
                    long delta = saturatedAdd(gap, -reported);
                    if (delta != 0L) {
                        deltas.put(uuid, delta);
                    }
                    reported = gap;
                }
                pending.put(uuid, new Base(newVersion, value, s.remainingSeconds(), reported));
                return true;
            }
            // Conflit : un autre écrivain a modifié (ou créé/supprimé) la ligne. Relecture puis ré-application du delta.
            Current cur = lockingRead(c, uuid);
            if (cur == null) {
                // Ligne supprimée entre-temps : on recrée la ligne avec la valeur locale.
                base = new Base(ABSENT, s.remainingSeconds(), s.remainingSeconds(), base.reported());
            } else {
                base = new Base(cur.version(), cur.remaining(), base.localRemaining(), base.reported());
            }
            logger.fine("Conflit de version sur le profil " + uuid + " (tentative " + attempt + ").");
        }
        logger.warning("Profil " + uuid + " non écrit : " + MAX_WRITE_ATTEMPTS
                + " conflits de version consécutifs (écritures concurrentes d'un autre serveur ?)."
                + " Nouvel essai au prochain flush.");
        return false;
    }

    /** Valeur à écrire : temps en base + delta local depuis la dernière synchronisation (saturé, ≥ 0). */
    static long merge(Base base, long localRemaining) {
        // localRemaining de la base peut être négatif après absorption d'un écart distant borné : soustraction
        // saturée (les valeurs réelles restent bornées par limits.max-seconds).
        long delta;
        try {
            delta = Math.subtractExact(localRemaining, base.localRemaining());
        } catch (ArithmeticException e) {
            delta = localRemaining > base.localRemaining() ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return Math.max(0L, saturatedAdd(base.dbRemaining(), delta));
    }

    /** Addition saturée (jamais de débordement silencieux). */
    static long saturatedAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return b > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private record Current(long remaining, long version) {
    }

    private @Nullable Current lockingRead(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(lockingReadSql())) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Current(Math.max(0L, rs.getLong(1)), rs.getLong(2)) : null;
            }
        }
    }

    private int insertIfAbsent(Connection c, FlightProfileSnapshot s, long value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(insertIfAbsentSql())) {
            ps.setString(1, s.uuid().toString());
            ps.setString(2, safeName(s.lastKnownName()));
            ps.setLong(3, value);
            ps.setBoolean(4, s.flightEnabled());
            ps.setLong(5, s.lastUpdateEpochSeconds());
            ps.setLong(6, s.cooldownUntilEpochSeconds());
            ps.setDouble(7, s.flySpeed());
            return ps.executeUpdate();
        }
    }

    private static int updateIfVersion(Connection c, FlightProfileSnapshot s, long value, long version)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(UPDATE_IF_VERSION_SQL)) {
            ps.setString(1, safeName(s.lastKnownName()));
            ps.setLong(2, value);
            ps.setBoolean(3, s.flightEnabled());
            ps.setLong(4, s.lastUpdateEpochSeconds());
            ps.setLong(5, s.cooldownUntilEpochSeconds());
            ps.setDouble(6, s.flySpeed());
            ps.setString(7, s.uuid().toString());
            ps.setLong(8, version);
            return ps.executeUpdate();
        }
    }

    /** Défense en profondeur : seul un pseudo valide (≤ 16 caractères) est écrit. */
    private static String safeName(String name) {
        return name != null && FlightProfile.NAME_PATTERN.matcher(name).matches() ? name : "";
    }

    // ------------------------------------------------------------------ utilitaires

    /**
     * Corps d'une opération SQL avec résultat.
     *
     * @param <T> type du résultat
     */
    @FunctionalInterface
    protected interface SqlSupplier<T> {
        /**
         * @return résultat
         * @throws SQLException erreur SQL
         */
        T get() throws SQLException;
    }

    /** Corps d'une opération SQL sans résultat. */
    @FunctionalInterface
    protected interface SqlRunnable {
        /**
         * @throws SQLException erreur SQL
         */
        void run() throws SQLException;
    }

    /**
     * Exécute {@code body} sur l'executor I/O ; les {@link SQLException} deviennent des {@link StorageException}.
     *
     * @param operation libellé (logs/erreurs, sans secret)
     * @param body      corps
     * @param <T>       type du résultat
     * @return future complétée sur le thread I/O
     */
    protected final <T> CompletableFuture<T> supply(String operation, SqlSupplier<T> body) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return body.get();
                } catch (SQLException e) {
                    throw new StorageException("Échec SQL (" + operation + ") : " + e.getMessage(), e);
                }
            }, io);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(
                    new StorageException("Stockage arrêté, opération refusée : " + operation, e));
        }
    }

    /**
     * Variante sans résultat de {@link #supply(String, SqlSupplier)}.
     *
     * @param operation libellé
     * @param body      corps
     * @return future complétée sur le thread I/O
     */
    protected final CompletableFuture<Void> run(String operation, SqlRunnable body) {
        return supply(operation, () -> {
            body.run();
            return null;
        });
    }

    /**
     * @return connexion du pool
     * @throws SQLException si le dépôt n'est pas initialisé ou est fermé
     */
    protected final Connection connection() throws SQLException {
        HikariDataSource ds = dataSource;
        if (closed || ds == null) {
            throw new SQLException(closed ? "dépôt fermé" : "dépôt non initialisé (init() non appelé)");
        }
        return ds.getConnection();
    }

    /** Lit la ligne courante et pose la base (sans écraser une base existante, sauf {@code force}). */
    private @Nullable FlightProfileSnapshot readAndTrack(ResultSet rs, boolean force) throws SQLException {
        FlightProfileSnapshot s = readSnapshot(rs);
        if (s != null) {
            Base b = new Base(rs.getLong("version"), s.remainingSeconds(), s.remainingSeconds());
            if (force) {
                bases.put(s.uuid(), b);
            } else {
                bases.putIfAbsent(s.uuid(), b);
            }
        }
        return s;
    }

    private @Nullable FlightProfileSnapshot readSnapshot(ResultSet rs) throws SQLException {
        String rawUuid = rs.getString("uuid");
        UUID uuid;
        try {
            if (rawUuid == null) {
                throw new IllegalArgumentException("uuid nul");
            }
            uuid = UUID.fromString(rawUuid.trim());
        } catch (IllegalArgumentException e) {
            logger.warning("Ligne aafly_profiles ignorée : UUID invalide.");
            return null;
        }
        String name = rs.getString("name");
        if (name == null || !FlightProfile.NAME_PATTERN.matcher(name).matches()) {
            name = "";
        }
        // Les bornes (temps ≥ 0, vitesse 0.1–1.0, NaN) sont réappliquées par FlightProfileSnapshot.
        return new FlightProfileSnapshot(uuid, name,
                rs.getLong("remaining_seconds"),
                rs.getBoolean("flight_enabled"),
                rs.getLong("last_update"),
                rs.getLong("cooldown_until"),
                rs.getDouble("fly_speed"));
    }

    private static void rollbackQuietly(Connection c, Exception cause) {
        try {
            c.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return msg == null ? root.getClass().getSimpleName() : msg;
    }
}
