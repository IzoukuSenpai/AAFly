package com.azurhosts.aafly.migration;

import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.storage.MySqlProfileRepository;
import com.azurhosts.aafly.storage.StorageException;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.sqlite.SQLiteOpenMode;

/**
 * Lecture des sources de migration (SPEC-phase2 A9 et A10), toujours en <b>lecture seule</b> et sur des
 * chemins fixes sous {@code plugins} :
 * <ul>
 *   <li>wFly SQLite : {@code plugins/wFlyV2/database.db}, table {@code fly}
 *       ({@code uniqueId, isinFly, FlyTimeRemaining, lastUpdate}) ouverte en {@code mode=ro} ;</li>
 *   <li>wFly MySQL : mêmes colonnes ; connexion lue dans {@code plugins/wFlyV2/config.yml} (section
 *       {@code mysql}), jamais journalisée, session {@code READ ONLY} ;</li>
 *   <li>TempFly : {@code plugins/TempFly/data.yml}, clés {@code players.<uuid>.time} (secondes).</li>
 * </ul>
 * La clé {@code redis} d'une configuration wFly est ignorée avec un avertissement (A10).
 * Aucune méthode ne touche à l'API Bukkit : à appeler hors du main thread.
 */
public final class MigrationSources {

    /** Dossier de wFly sous {@code plugins}. */
    public static final String WFLY_DIR = "wFlyV2";
    /** Base SQLite de wFly. */
    public static final String WFLY_DB = "database.db";
    /** Configuration de wFly. */
    public static final String WFLY_CONFIG = "config.yml";
    /** Dossier de TempFly sous {@code plugins}. */
    public static final String TEMPFLY_DIR = "TempFly";
    /** Données de TempFly. */
    public static final String TEMPFLY_DATA = "data.yml";

    private static final String WFLY_SELECT = "SELECT uniqueId, isinFly, FlyTimeRemaining FROM fly";
    private static final List<String> WFLY_COLUMNS = List.of("uniqueid", "isinfly", "flytimeremaining");

    private MigrationSources() {
    }

    // ------------------------------------------------------------------ wFly SQLite

    /**
     * @param pluginsDir dossier {@code plugins}
     * @param logger     journal (avertissement redis)
     * @return lignes brutes
     * @throws MigrationException source absente, illisible ou au format inattendu
     */
    public static List<SourceRow> readWFlySqlite(Path pluginsDir, Logger logger) {
        warnIfRedis(pluginsDir, logger);
        Path db = SafePaths.resolveFile(pluginsDir, WFLY_DIR, WFLY_DB);
        return readWFlySqliteFile(db);
    }

    /**
     * Lit une base wFly SQLite (déjà résolue) en lecture seule.
     *
     * @param db fichier
     * @return lignes brutes
     */
    static List<SourceRow> readWFlySqliteFile(Path db) {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setOpenMode(SQLiteOpenMode.READONLY);
        config.setBusyTimeout(5_000);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        // URI file:...?mode=ro : la base n'est jamais créée ni modifiée (en plus du drapeau READONLY).
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath().toUri() + "?mode=ro");
        try (Connection c = ds.getConnection()) {
            return readWFlyTable(c, "wFly (SQLite)");
        } catch (SQLException e) {
            throw new MigrationException("Lecture de plugins/" + WFLY_DIR + "/" + WFLY_DB + " impossible : "
                    + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ wFly MySQL

    /**
     * @param pluginsDir dossier {@code plugins}
     * @param logger     journal (avertissement redis ; la connexion n'est jamais journalisée)
     * @return lignes brutes
     * @throws MigrationException configuration absente/invalide, connexion impossible, table absente
     */
    public static List<SourceRow> readWFlyMysql(Path pluginsDir, Logger logger) {
        Path configFile = SafePaths.resolveFile(pluginsDir, WFLY_DIR, WFLY_CONFIG);
        Map<?, ?> root = SafeYaml.loadMap(configFile);
        if (root == null) {
            throw new MigrationException("plugins/" + WFLY_DIR + "/" + WFLY_CONFIG + " : format non reconnu.");
        }
        warnIfRedis(root, logger);
        Settings.MysqlSection mysql = wflyMysqlSection(root);
        HikariDataSource ds;
        try {
            ds = new HikariDataSource(MySqlProfileRepository.readOnlyConfig(mysql, "AAFly-Migration-wFly"));
        } catch (StorageException e) {
            throw new MigrationException("Section mysql de wFly invalide : " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new MigrationException("Connexion à la base MySQL de wFly impossible (" + mysql.host() + ":"
                    + mysql.port() + "/" + mysql.database() + ") : " + rootMessage(e), e);
        }
        try (ds; Connection c = ds.getConnection()) {
            if (!c.isReadOnly()) {
                throw new MigrationException("La connexion MySQL de wFly n'a pas pu être ouverte en lecture seule.");
            }
            return readWFlyTable(c, "wFly (MySQL)");
        } catch (SQLException e) {
            throw new MigrationException("Lecture de la base MySQL de wFly impossible : " + e.getMessage(), e);
        }
    }

    /**
     * Construit les paramètres de connexion depuis la section {@code mysql} de wFly (noms de clés usuels
     * acceptés). Le mot de passe n'apparaît que dans l'objet retourné (masqué par son {@code toString}).
     *
     * @param root racine de {@code config.yml}
     * @return paramètres
     * @throws MigrationException si la section est absente ou incomplète
     */
    static Settings.MysqlSection wflyMysqlSection(Map<?, ?> root) {
        Map<?, ?> section = mapValue(root, "mysql", "MySQL", "database", "sql");
        if (section == null) {
            throw new MigrationException("plugins/" + WFLY_DIR + "/" + WFLY_CONFIG + " : section mysql absente.");
        }
        String host = text(section, "host", "hostname", "address", "ip");
        String database = text(section, "database", "db", "name", "database-name");
        String user = text(section, "username", "user");
        String password = text(section, "password", "pass");
        String portRaw = text(section, "port");
        String ssl = text(section, "ssl", "useSSL", "use-ssl", "usessl");
        if (host == null || database == null || user == null) {
            throw new MigrationException("Section mysql de wFly incomplète (host, database et username requis).");
        }
        int port = 3306;
        if (portRaw != null) {
            try {
                port = Integer.parseInt(portRaw.trim());
            } catch (NumberFormatException e) {
                throw new MigrationException("Section mysql de wFly : port invalide.");
            }
        }
        return new Settings.MysqlSection(host.trim(), port, database.trim(), user, password == null ? "" : password,
                "true".equalsIgnoreCase(ssl == null ? "" : ssl.trim()), 1);
    }

    // ------------------------------------------------------------------ TempFly

    /**
     * @param pluginsDir dossier {@code plugins}
     * @return lignes brutes ({@code players.<uuid>.time})
     * @throws MigrationException fichier absent ou format non reconnu (rien n'est écrit)
     */
    public static List<SourceRow> readTempFly(Path pluginsDir) {
        Path data = SafePaths.resolveFile(pluginsDir, TEMPFLY_DIR, TEMPFLY_DATA);
        return readTempFlyFile(data);
    }

    /**
     * @param data fichier {@code data.yml} (déjà résolu)
     * @return lignes brutes
     */
    static List<SourceRow> readTempFlyFile(Path data) {
        Map<?, ?> root = SafeYaml.loadMap(data);
        Object players = root == null ? null : root.get("players");
        if (!(players instanceof Map<?, ?> map)) {
            throw new MigrationException("plugins/" + TEMPFLY_DIR + "/" + TEMPFLY_DATA
                    + " : format non reconnu (section players.<uuid>.time attendue). Aucune donnée importée.");
        }
        if (map.size() > ProfileImporter.MAX_ROWS) {
            throw new MigrationException("Trop de joueurs dans la source (" + map.size() + ").");
        }
        List<SourceRow> rows = new ArrayList<>(map.size());
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object time = e.getValue() instanceof Map<?, ?> p ? p.get("time") : null;
            rows.add(new SourceRow(e.getKey(), time, null));
        }
        return rows;
    }

    // ------------------------------------------------------------------ communs

    private static List<SourceRow> readWFlyTable(Connection c, String label) throws SQLException {
        checkWFlyTable(c, label);
        List<SourceRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(WFLY_SELECT)) {
            ps.setFetchSize(1_000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rows.size() >= ProfileImporter.MAX_ROWS) {
                        throw new MigrationException("Trop de lignes dans la source " + label + " (maximum "
                                + ProfileImporter.MAX_ROWS + ").");
                    }
                    rows.add(new SourceRow(rs.getObject(1), rs.getObject(3), rs.getObject(2)));
                }
            }
        }
        return rows;
    }

    private static void checkWFlyTable(Connection c, String label) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        List<String> found = new ArrayList<>();
        for (String table : List.of("fly", "FLY")) {
            try (ResultSet rs = meta.getColumns(c.getCatalog(), null, table, null)) {
                while (rs.next()) {
                    found.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            if (!found.isEmpty()) {
                break;
            }
        }
        if (found.isEmpty()) {
            throw new MigrationException("Source " + label + " : table fly introuvable. Aucune donnée importée.");
        }
        for (String col : WFLY_COLUMNS) {
            if (!found.contains(col)) {
                throw new MigrationException("Source " + label + " : colonne " + col
                        + " absente de la table fly (format non reconnu). Aucune donnée importée.");
            }
        }
    }

    /** A10 : la clé redis d'une configuration wFly est ignorée avec WARN (le fichier peut manquer). */
    private static void warnIfRedis(Path pluginsDir, Logger logger) {
        Path configFile;
        try {
            configFile = SafePaths.resolveOptionalFile(pluginsDir, WFLY_DIR, WFLY_CONFIG);
        } catch (MigrationException e) {
            logger.warning("Configuration wFly ignorée : " + e.getMessage());
            return;
        }
        if (configFile == null) {
            return;
        }
        try {
            Map<?, ?> root = SafeYaml.loadMap(configFile);
            if (root != null) {
                warnIfRedis(root, logger);
            }
        } catch (MigrationException e) {
            logger.warning("Configuration wFly illisible, ignorée : " + e.getMessage());
        }
    }

    static boolean warnIfRedis(Map<?, ?> root, Logger logger) {
        for (Object key : root.keySet()) {
            if (key != null && "redis".equalsIgnoreCase(key.toString())) {
                logger.warning("Clé « redis » de la configuration wFly ignorée : Redis n'est pas pris en charge"
                        + " par AAFly (le multi-serveur passe par MySQL et le verrou optimiste). Si wFly utilisait"
                        + " Redis, vérifiez que sa base SQL contient bien les données à migrer.");
                return true;
            }
        }
        return false;
    }

    private static @Nullable Map<?, ?> mapValue(Map<?, ?> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v instanceof Map<?, ?> m) {
                return m;
            }
        }
        return null;
    }

    private static @Nullable String text(Map<?, ?> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !(v instanceof Map) && !(v instanceof List)) {
                return v.toString();
            }
        }
        return null;
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
