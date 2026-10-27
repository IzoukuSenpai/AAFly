package com.azurhosts.aafly.storage;

import com.azurhosts.aafly.config.Settings;
import com.zaxxer.hikari.HikariConfig;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Dépôt MySQL / MariaDB partagé (pool HikariCP).
 *
 * <p><b>Sécurité :</b>
 * <ul>
 *   <li>hôte, port et base sont validés strictement avant d'entrer dans l'URL JDBC (aucun paramètre
 *       d'URL injectable via la config) ; utilisateur et mot de passe passent par les propriétés du pool,
 *       jamais par l'URL ni par les logs ;</li>
 *   <li>options dangereuses du pilote désactivées explicitement : {@code allowLoadLocalInfile},
 *       {@code allowUrlInLocalInfile}, {@code autoDeserialize}, {@code allowMultiQueries},
 *       {@code allowPublicKeyRetrieval} ;</li>
 *   <li>{@code use-ssl: true} → {@code sslMode=REQUIRED} ; {@code false} → {@code PREFERRED}
 *       (chiffrement opportuniste, jamais de rétrogradation volontaire).</li>
 * </ul>
 * La colonne {@code name} est en {@code ascii_general_ci} : recherche par pseudo insensible à la casse
 * et indexée.
 */
public final class MySqlProfileRepository extends AbstractSqlProfileRepository {

    /** Taille maximale acceptée pour le pool. */
    public static final int MAX_POOL_SIZE = 32;

    private static final Pattern HOSTNAME = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9.\\-]{0,252})$");
    private static final Pattern IPV6 = Pattern.compile("^\\[[0-9A-Fa-f:.]{2,45}]$");
    private static final Pattern DATABASE = Pattern.compile("^[A-Za-z0-9_]{1,64}$");
    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    private static final String UPSERT_SQL =
            "INSERT INTO aafly_profiles (" + PROFILE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE"
                    + " name = VALUES(name),"
                    + " remaining_seconds = VALUES(remaining_seconds),"
                    + " flight_enabled = VALUES(flight_enabled),"
                    + " last_update = VALUES(last_update),"
                    + " cooldown_until = VALUES(cooldown_until),"
                    + " fly_speed = VALUES(fly_speed)";

    private static final String FIND_BY_NAME_SQL =
            "SELECT " + PROFILE_COLUMNS + " FROM aafly_profiles WHERE name = ?"
                    + " ORDER BY last_update DESC LIMIT 1";

    private static final SchemaMigrator.Dialect DIALECT = new SchemaMigrator.Dialect() {
        @Override
        public String createSchemaTable() {
            return "CREATE TABLE IF NOT EXISTS aafly_schema (version INT NOT NULL, PRIMARY KEY (version))"
                    + " ENGINE=InnoDB";
        }

        @Override
        public String recordVersion() {
            return "INSERT IGNORE INTO aafly_schema (version) VALUES (?)";
        }

        @Override
        public List<String> migration(int version) {
            if (version == 1) {
                return List.of(
                        "CREATE TABLE IF NOT EXISTS aafly_profiles ("
                                + "uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                                + " name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL DEFAULT '',"
                                + " remaining_seconds BIGINT NOT NULL DEFAULT 0,"
                                + " flight_enabled BOOLEAN NOT NULL DEFAULT FALSE,"
                                + " last_update BIGINT NOT NULL DEFAULT 0,"
                                + " cooldown_until BIGINT NOT NULL DEFAULT 0,"
                                + " fly_speed DOUBLE NOT NULL DEFAULT 0.1,"
                                + " PRIMARY KEY (uuid),"
                                + " INDEX idx_aafly_profiles_name (name)"
                                + ") ENGINE=InnoDB",
                        "CREATE TABLE IF NOT EXISTS aafly_tokens ("
                                + "token_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                                + " seconds BIGINT NOT NULL,"
                                + " created_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,"
                                + " created_at BIGINT NOT NULL,"
                                + " redeemed_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,"
                                + " redeemed_at BIGINT NULL,"
                                + " PRIMARY KEY (token_id)"
                                + ") ENGINE=InnoDB");
            }
            throw new IllegalArgumentException("Migration MySQL inconnue : " + version);
        }
    };

    private final Settings.MysqlSection settings;
    private final int poolSize;

    /**
     * Valide la configuration (sans se connecter : la connexion a lieu dans {@link #init()}).
     *
     * @param settings section {@code storage.mysql}
     * @param io       executor I/O dédié
     * @param logger   journal du plugin
     * @throws StorageException si l'hôte, le port ou la base sont invalides
     */
    public MySqlProfileRepository(Settings.MysqlSection settings, Executor io, Logger logger) {
        super(io, logger);
        this.settings = Objects.requireNonNull(settings, "settings");
        validate(settings);
        int requested = settings.poolSize();
        this.poolSize = Math.max(1, Math.min(MAX_POOL_SIZE, requested));
        if (poolSize != requested) {
            logger.warning("storage.mysql.pool-size=" + requested + " hors bornes [1, " + MAX_POOL_SIZE
                    + "], valeur retenue : " + poolSize + ".");
        }
    }

    /**
     * Vérifie les paramètres qui entrent dans l'URL JDBC.
     *
     * @param s section MySQL
     * @throws StorageException si un paramètre est invalide (message sans mot de passe)
     */
    static void validate(Settings.MysqlSection s) {
        String host = s.host();
        if (host == null || !(HOSTNAME.matcher(host).matches() || IPV6.matcher(host).matches())) {
            throw new StorageException("storage.mysql.host invalide (nom d'hôte ou IP attendu, sans paramètre).");
        }
        if (s.port() < 1 || s.port() > 65_535) {
            throw new StorageException("storage.mysql.port invalide (1 à 65535) : " + s.port());
        }
        if (s.database() == null || !DATABASE.matcher(s.database()).matches()) {
            throw new StorageException("storage.mysql.database invalide (lettres, chiffres, _ ; 64 max).");
        }
        if (s.username() == null || s.username().isBlank() || s.username().length() > 80) {
            throw new StorageException("storage.mysql.username invalide (non vide, 80 caractères max).");
        }
    }

    /**
     * @return URL JDBC construite uniquement à partir de valeurs validées (sans identifiants)
     */
    String jdbcUrl() {
        return "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database();
    }

    @Override
    protected HikariConfig createPoolConfig() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("AAFly-MySQL");
        config.setDriverClassName(DRIVER_CLASS);
        config.setJdbcUrl(jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(Math.min(2, poolSize));
        config.setConnectionTimeout(10_000L);
        config.setValidationTimeout(5_000L);
        config.setMaxLifetime(1_800_000L);
        config.setKeepaliveTime(300_000L);

        // Sécurité du pilote
        config.addDataSourceProperty("sslMode", settings.useSsl() ? "REQUIRED" : "PREFERRED");
        config.addDataSourceProperty("allowLoadLocalInfile", "false");
        config.addDataSourceProperty("allowUrlInLocalInfile", "false");
        config.addDataSourceProperty("autoDeserialize", "false");
        config.addDataSourceProperty("allowMultiQueries", "false");
        config.addDataSourceProperty("allowPublicKeyRetrieval", "false");
        // Performance
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "64");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "1024");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("characterEncoding", "UTF-8");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        return config;
    }

    @Override
    protected SchemaMigrator.Dialect dialect() {
        return DIALECT;
    }

    @Override
    protected String upsertSql() {
        return UPSERT_SQL;
    }

    @Override
    protected String findByNameSql() {
        return FIND_BY_NAME_SQL;
    }

    @Override
    public String describe() {
        return "MySQL " + settings.host() + ":" + settings.port() + "/" + settings.database();
    }
}
