package com.azurhosts.aafly.storage;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Crée et fait évoluer le schéma SQL d'AAFly (SPEC §3, SPEC-phase2 A5).
 *
 * <p>La table {@code aafly_schema(version INT PRIMARY KEY)} contient une ligne par migration appliquée ;
 * la version courante est {@code MAX(version)}. Historique :
 * <ol>
 *   <li>schéma de la phase 1 : {@code aafly_profiles}, {@code aafly_tokens} ;</li>
 *   <li>phase 2 : colonne {@code aafly_profiles.version BIGINT} (verrou optimiste) et table
 *       {@code aafly_server(key, value)} (heure d'arrêt {@code shutdown_at}).</li>
 * </ol>
 *
 * <p><b>Idempotence :</b> chaque étape peut être rejouée sans effet ({@code IF NOT EXISTS} ; l'ajout de
 * colonne consulte d'abord les métadonnées et tolère qu'un autre serveur l'ait ajoutée entre-temps).
 * Un arrêt brutal entre une étape et l'enregistrement de la version est donc sans conséquence : la
 * migration est rejouée au démarrage suivant. L'enregistrement de version utilise un « insert ignore » :
 * deux serveurs qui démarrent en même temps sur la même base MySQL ne se gênent pas. Un schéma plus
 * récent que ce que connaît le plugin est refusé (pas de rétrogradation silencieuse).
 *
 * <p>Toutes les requêtes passent par {@link PreparedStatement} ; les identifiants sont des constantes.
 * À n'appeler que depuis le thread I/O.
 */
public final class SchemaMigrator {

    /** Dernière version du schéma connue de cette version du plugin. */
    public static final int LATEST_VERSION = 2;

    /** Étape d'une migration. */
    public sealed interface Step permits Sql, AddColumn {
    }

    /**
     * Ordre DDL constant, idempotent par lui-même ({@code CREATE TABLE IF NOT EXISTS}, ...).
     *
     * @param sql ordre SQL constant
     */
    public record Sql(String sql) implements Step {
        public Sql {
            Objects.requireNonNull(sql, "sql");
        }
    }

    /**
     * Ajout de colonne, exécuté seulement si la colonne est absente.
     *
     * @param table  table (constante)
     * @param column colonne (constante)
     * @param ddl    ordre {@code ALTER TABLE ... ADD COLUMN ...} (constant)
     */
    public record AddColumn(String table, String column, String ddl) implements Step {
        public AddColumn {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(ddl, "ddl");
        }
    }

    /** Ordres SQL propres à un moteur. */
    public interface Dialect {

        /**
         * @return DDL de création de {@code aafly_schema} (idempotente)
         */
        String createSchemaTable();

        /**
         * @return requête d'enregistrement d'une version, un paramètre ({@code version}), ignorant les doublons
         */
        String recordVersion();

        /**
         * @param version version cible (1 à {@link #LATEST_VERSION})
         * @return étapes (constantes, idempotentes) qui amènent le schéma de {@code version - 1} à {@code version}
         */
        List<Step> migration(int version);
    }

    private static final String CURRENT_VERSION_SQL = "SELECT MAX(version) FROM aafly_schema";

    private final Dialect dialect;
    private final Logger logger;

    /**
     * @param dialect ordres du moteur
     * @param logger  journal du plugin
     */
    public SchemaMigrator(Dialect dialect, Logger logger) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Amène le schéma à {@link #LATEST_VERSION}.
     *
     * @param connection connexion (auto-commit quelconque)
     * @return version du schéma après migration
     * @throws SQLException     erreur SQL
     * @throws StorageException si la base a un schéma plus récent que le plugin
     */
    public int migrate(Connection connection) throws SQLException {
        execute(connection, dialect.createSchemaTable());
        int current = currentVersion(connection);
        if (current > LATEST_VERSION) {
            throw new StorageException("Le schéma de la base (version " + current
                    + ") est plus récent que celui d'AAFly (version " + LATEST_VERSION
                    + "). Mettez AAFly à jour ; aucune modification n'a été faite.");
        }
        for (int version = current + 1; version <= LATEST_VERSION; version++) {
            for (Step step : dialect.migration(version)) {
                apply(connection, step);
            }
            try (PreparedStatement ps = connection.prepareStatement(dialect.recordVersion())) {
                ps.setInt(1, version);
                ps.executeUpdate();
            }
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
            logger.info("Schéma de stockage migré vers la version " + version + ".");
        }
        return Math.max(current, LATEST_VERSION);
    }

    private static void apply(Connection connection, Step step) throws SQLException {
        switch (step) {
            case Sql s -> execute(connection, s.sql());
            case AddColumn a -> {
                if (columnExists(connection, a.table(), a.column())) {
                    return;
                }
                try {
                    execute(connection, a.ddl());
                } catch (SQLException e) {
                    // Course possible en MySQL multi-serveurs : un autre serveur vient d'ajouter la colonne.
                    if (!columnExists(connection, a.table(), a.column())) {
                        throw e;
                    }
                }
            }
        }
    }

    /**
     * @param connection connexion
     * @param table      table (minuscules)
     * @param column     colonne
     * @return {@code true} si la colonne existe dans la base courante de la connexion
     * @throws SQLException erreur SQL
     */
    public static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        // Catalogue courant : en MySQL, limite la recherche à la base configurée (pas aux autres bases).
        String catalog = connection.getCatalog();
        for (String t : List.of(table, table.toUpperCase(Locale.ROOT))) {
            try (ResultSet rs = meta.getColumns(catalog, null, t, null)) {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @param connection connexion
     * @return version courante (0 si aucune migration appliquée)
     * @throws SQLException erreur SQL
     */
    public static int currentVersion(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(CURRENT_VERSION_SQL);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                int v = rs.getInt(1);
                return rs.wasNull() ? 0 : v;
            }
            return 0;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.execute();
        }
    }
}
