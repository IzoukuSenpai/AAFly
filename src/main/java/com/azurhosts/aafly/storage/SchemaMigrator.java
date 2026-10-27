package com.azurhosts.aafly.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Crée et fait évoluer le schéma SQL d'AAFly (SPEC §3).
 *
 * <p>La table {@code aafly_schema(version INT PRIMARY KEY)} contient une ligne par migration appliquée ;
 * la version courante est {@code MAX(version)}. Chaque migration est idempotente ({@code IF NOT EXISTS})
 * et l'enregistrement de version utilise un « insert ignore » : deux serveurs qui démarrent en même temps
 * sur la même base MySQL ne se gênent pas. Un schéma plus récent que ce que connaît le plugin est refusé
 * (pas de rétrogradation silencieuse).
 *
 * <p>Toutes les requêtes passent par {@link PreparedStatement} ; les identifiants sont des constantes.
 * À n'appeler que depuis le thread I/O.
 */
public final class SchemaMigrator {

    /** Dernière version du schéma connue de cette version du plugin. */
    public static final int LATEST_VERSION = 1;

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
         * @return ordres DDL (constants, idempotents) qui amènent le schéma à cette version
         */
        List<String> migration(int version);
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
     * @param connection connexion (auto-commit quelconque, restauré à la sortie)
     * @return version du schéma après migration
     * @throws SQLException          erreur SQL
     * @throws StorageException      si la base a un schéma plus récent que le plugin
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
            List<String> statements = dialect.migration(version);
            for (String sql : statements) {
                execute(connection, sql);
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
