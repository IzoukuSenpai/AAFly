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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Socle commun des dépôts SQL (SQLite, MySQL) : pool HikariCP, requêtes préparées, transactions.
 *
 * <p><b>Threads :</b> chaque opération est soumise à l'executor I/O fourni (un seul thread
 * {@code AAFly-IO} : l'ordre des écritures et des lectures est donc celui des appels). Le pool est créé
 * dans {@link #init()}, sur ce thread, pour ne jamais bloquer le main thread sur une connexion réseau.
 * Aucun appel à l'API Bukkit ici.
 *
 * <p><b>Sécurité :</b> uniquement des {@link PreparedStatement} à paramètres liés, noms de tables et de
 * colonnes constants, aucune valeur concaténée dans le SQL ; le mot de passe n'apparaît dans aucun log.
 */
public abstract sealed class AbstractSqlProfileRepository implements ProfileRepository
        permits SqliteProfileRepository, MySqlProfileRepository {

    /** Nombre maximal de lignes par lot JDBC dans {@link #saveAll(Collection)}. */
    static final int BATCH_SIZE = 500;

    /** Colonnes de {@code aafly_profiles}, dans l'ordre des paramètres d'upsert. */
    protected static final String PROFILE_COLUMNS =
            "uuid, name, remaining_seconds, flight_enabled, last_update, cooldown_until, fly_speed";

    private static final String FIND_SQL =
            "SELECT " + PROFILE_COLUMNS + " FROM aafly_profiles WHERE uuid = ?";
    private static final String FIND_ALL_SQL =
            "SELECT " + PROFILE_COLUMNS + " FROM aafly_profiles";
    private static final String INSERT_TOKEN_SQL =
            "INSERT INTO aafly_tokens (token_id, seconds, created_by, created_at, redeemed_by, redeemed_at)"
                    + " VALUES (?, ?, ?, ?, NULL, NULL)";
    private static final String REDEEM_TOKEN_SQL =
            "UPDATE aafly_tokens SET redeemed_by = ?, redeemed_at = ?"
                    + " WHERE token_id = ? AND seconds = ? AND redeemed_at IS NULL";

    /** Executor I/O ({@code AAFly-IO}). */
    protected final Executor io;
    /** Journal du plugin. */
    protected final Logger logger;

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
     * @return upsert d'un profil, 7 paramètres dans l'ordre de {@link #PROFILE_COLUMNS}
     */
    protected abstract String upsertSql();

    /**
     * @return recherche insensible à la casse par pseudo, 1 paramètre, le plus récent d'abord, 1 ligne max
     */
    protected abstract String findByNameSql();

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
                    return rs.next() ? Optional.ofNullable(readSnapshot(rs)) : Optional.empty();
                }
            }
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
                    return rs.next() ? Optional.ofNullable(readSnapshot(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> upsert(FlightProfileSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return run("sauvegarde du profil " + snapshot.uuid(), () -> {
            try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(upsertSql())) {
                bindProfile(ps, snapshot);
                ps.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<FlightProfileSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        List<FlightProfileSnapshot> copy = List.copyOf(snapshots);
        if (copy.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return run("sauvegarde groupée de " + copy.size() + " profil(s)", () -> {
            try (Connection c = connection()) {
                boolean previousAutoCommit = c.getAutoCommit();
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(upsertSql())) {
                    int pending = 0;
                    for (FlightProfileSnapshot s : copy) {
                        bindProfile(ps, s);
                        ps.addBatch();
                        if (++pending >= BATCH_SIZE) {
                            ps.executeBatch();
                            pending = 0;
                        }
                    }
                    if (pending > 0) {
                        ps.executeBatch();
                    }
                    c.commit();
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
            try (Connection c = connection()) {
                boolean previousAutoCommit = c.getAutoCommit();
                c.setAutoCommit(false);
                try (PreparedStatement profile = c.prepareStatement(upsertSql());
                     PreparedStatement insert = c.prepareStatement(INSERT_TOKEN_SQL)) {
                    bindProfile(profile, debited);
                    profile.executeUpdate();
                    executeInsertToken(insert, token);
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    rollbackQuietly(c, e);
                    throw e;
                } finally {
                    c.setAutoCommit(previousAutoCommit);
                }
            }
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
    }

    /**
     * @return {@code true} après {@link #close()}
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Lit tous les profils valides (diagnostic / tests ; thread I/O).
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

    private static void bindProfile(PreparedStatement ps, FlightProfileSnapshot s) throws SQLException {
        ps.setString(1, s.uuid().toString());
        String name = s.lastKnownName();
        // Défense en profondeur : seul un pseudo valide (≤ 16 caractères) est écrit.
        ps.setString(2, FlightProfile.NAME_PATTERN.matcher(name).matches() ? name : "");
        ps.setLong(3, s.remainingSeconds());
        ps.setBoolean(4, s.flightEnabled());
        ps.setLong(5, s.lastUpdateEpochSeconds());
        ps.setLong(6, s.cooldownUntilEpochSeconds());
        ps.setDouble(7, s.flySpeed());
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
