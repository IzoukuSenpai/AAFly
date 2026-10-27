package com.azurhosts.aafly.storage;

import com.zaxxer.hikari.HikariConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Dépôt SQLite (fichier local {@code plugins/AAFly/aafly.db}).
 *
 * <p>Pool HikariCP d'une seule connexion (SQLite n'a qu'un écrivain ; l'executor I/O est mono-thread),
 * journal WAL, {@code synchronous=NORMAL}, {@code busy_timeout} 5 s. La source de données est
 * construite directement ({@link SQLiteDataSource}) : aucune dépendance au {@code DriverManager}.
 */
public final class SqliteProfileRepository extends AbstractSqlProfileRepository {

    /** Nom du fichier de base dans le dossier du plugin. */
    public static final String DEFAULT_FILE_NAME = "aafly.db";

    /** Insertion si absente : 0 ligne si la ligne existe déjà (conflit). */
    private static final String INSERT_IF_ABSENT_SQL =
            "INSERT INTO aafly_profiles (" + PROFILE_COLUMNS + ", version) VALUES (?, ?, ?, ?, ?, ?, ?, 1)"
                    + " ON CONFLICT(uuid) DO NOTHING";

    private static final String LOCKING_READ_SQL =
            "SELECT remaining_seconds, version FROM aafly_profiles WHERE uuid = ?";

    private static final String SERVER_UPSERT_SQL =
            "INSERT INTO aafly_server (\"key\", \"value\") VALUES (?, ?)"
                    + " ON CONFLICT(\"key\") DO UPDATE SET \"value\" = excluded.\"value\"";

    private static final String SERVER_SELECT_SQL =
            "SELECT \"value\" FROM aafly_server WHERE \"key\" = ?";

    private static final String FIND_BY_NAME_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM aafly_profiles WHERE name = ? COLLATE NOCASE"
                    + " ORDER BY last_update DESC LIMIT 1";

    private static final SchemaMigrator.Dialect DIALECT = new SchemaMigrator.Dialect() {
        @Override
        public String createSchemaTable() {
            return "CREATE TABLE IF NOT EXISTS aafly_schema (version INTEGER NOT NULL PRIMARY KEY)";
        }

        @Override
        public String recordVersion() {
            return "INSERT OR IGNORE INTO aafly_schema (version) VALUES (?)";
        }

        @Override
        public List<SchemaMigrator.Step> migration(int version) {
            if (version == 1) {
                return List.of(new SchemaMigrator.Sql(
                        "CREATE TABLE IF NOT EXISTS aafly_profiles ("
                                + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                                + " name VARCHAR(16) NOT NULL DEFAULT '',"
                                + " remaining_seconds BIGINT NOT NULL DEFAULT 0,"
                                + " flight_enabled BOOLEAN NOT NULL DEFAULT 0,"
                                + " last_update BIGINT NOT NULL DEFAULT 0,"
                                + " cooldown_until BIGINT NOT NULL DEFAULT 0,"
                                + " fly_speed DOUBLE NOT NULL DEFAULT 0.1)"),
                        new SchemaMigrator.Sql("CREATE INDEX IF NOT EXISTS idx_aafly_profiles_name"
                                + " ON aafly_profiles (name COLLATE NOCASE)"),
                        new SchemaMigrator.Sql("CREATE TABLE IF NOT EXISTS aafly_tokens ("
                                + "token_id CHAR(36) NOT NULL PRIMARY KEY,"
                                + " seconds BIGINT NOT NULL,"
                                + " created_by CHAR(36) NULL,"
                                + " created_at BIGINT NOT NULL,"
                                + " redeemed_by CHAR(36) NULL,"
                                + " redeemed_at BIGINT NULL)"));
            }
            if (version == 2) {
                return List.of(
                        new SchemaMigrator.AddColumn("aafly_profiles", "version",
                                "ALTER TABLE aafly_profiles ADD COLUMN version BIGINT NOT NULL DEFAULT 0"),
                        new SchemaMigrator.Sql("CREATE TABLE IF NOT EXISTS aafly_server ("
                                + "\"key\" VARCHAR(64) NOT NULL PRIMARY KEY,"
                                + " \"value\" BIGINT NOT NULL)"));
            }
            throw new IllegalArgumentException("Migration SQLite inconnue : " + version);
        }
    };

    private final Path file;

    /**
     * @param file   fichier de base (créé au besoin, dossier parent compris)
     * @param io     executor I/O dédié
     * @param logger journal du plugin
     */
    public SqliteProfileRepository(Path file, Executor io, Logger logger) {
        super(io, logger);
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    @Override
    protected void beforePoolCreation() throws SQLException {
        Path parent = file.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new SQLException("création du dossier de la base impossible : " + e.getMessage(), e);
            }
        }
    }

    @Override
    protected HikariConfig createPoolConfig() {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqlite.setBusyTimeout(5_000);
        sqlite.enforceForeignKeys(true);

        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + file);

        HikariConfig config = new HikariConfig();
        config.setPoolName("AAFly-SQLite");
        config.setDataSource(source);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000L);
        config.setMaxLifetime(0L); // fichier local : pas de recyclage nécessaire
        config.setIdleTimeout(0L);
        return config;
    }

    @Override
    protected SchemaMigrator.Dialect dialect() {
        return DIALECT;
    }

    @Override
    protected String findByNameSql() {
        return FIND_BY_NAME_SQL;
    }

    @Override
    protected String insertIfAbsentSql() {
        return INSERT_IF_ABSENT_SQL;
    }

    @Override
    protected String lockingReadSql() {
        return LOCKING_READ_SQL;
    }

    @Override
    protected String serverUpsertSql() {
        return SERVER_UPSERT_SQL;
    }

    @Override
    protected String serverSelectSql() {
        return SERVER_SELECT_SQL;
    }

    @Override
    public String describe() {
        return "SQLite " + file.getFileName();
    }

    /**
     * @return chemin absolu du fichier de base
     */
    public Path file() {
        return file;
    }
}
