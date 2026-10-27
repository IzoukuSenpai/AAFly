package com.azurhosts.aafly.migration;

import com.azurhosts.aafly.core.FlightProfile;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Import de lignes validées dans {@code aafly_profiles} (SPEC-phase2 A9), sur une connexion fournie
 * <b>dans une transaction</b> (voir {@code AbstractSqlProfileRepository#inTransaction}). Pur JDBC, aucun
 * appel Bukkit : testable sur SQLite.
 *
 * <ul>
 *   <li>Chaque ligne est validée ({@link SourceRow#validate(long)}) ; invalides et doublons sont comptés et
 *       ignorés.</li>
 *   <li>Profils existants : stratégie {@link MergeStrategy} ; toute mise à jour incrémente {@code version}
 *       et est conditionnée à la version lue ({@code WHERE version = ?}) : si un serveur écrit la ligne
 *       pendant l'import, l'import échoue en bloc (rollback) au lieu d'écraser une donnée plus récente.</li>
 *   <li>Créations : pseudo vide (inconnu), vitesse par défaut, aucune recharge, {@code version = 1}.</li>
 *   <li>{@code last_update} = instant de l'import pour toute ligne écrite (le décompte hors-ligne ne
 *       compte pas la durée de la migration).</li>
 *   <li>Essai à blanc : mêmes lectures et mêmes comptes, aucune écriture.</li>
 * </ul>
 * Requêtes préparées uniquement ; seul le nombre de paramètres {@code ?} d'un {@code IN (...)} varie.
 */
public final class ProfileImporter {

    /** Nombre maximal de lignes lues depuis une source (protection mémoire). */
    public static final int MAX_ROWS = 2_000_000;
    /** Taille des lots (lecture {@code IN} et écritures batch). */
    static final int CHUNK = 500;

    private static final String INSERT_SQL =
            "INSERT INTO aafly_profiles (uuid, name, remaining_seconds, flight_enabled, last_update,"
                    + " cooldown_until, fly_speed, version) VALUES (?, '', ?, ?, ?, 0, ?, 1)";
    private static final String UPDATE_MAX_SQL =
            "UPDATE aafly_profiles SET remaining_seconds = ?, last_update = ?, version = version + 1"
                    + " WHERE uuid = ? AND version = ?";
    private static final String UPDATE_REPLACE_SQL =
            "UPDATE aafly_profiles SET remaining_seconds = ?, flight_enabled = ?, last_update = ?,"
                    + " version = version + 1 WHERE uuid = ? AND version = ?";

    private record Existing(long remaining, boolean flying, long version) {
    }

    private ProfileImporter() {
    }

    /**
     * @param c            connexion AAFly (auto-commit désactivé ; commit/rollback par l'appelant)
     * @param rows         lignes brutes de la source
     * @param strategy     fusion avec les profils existants
     * @param dryRun       {@code true} : aucune écriture
     * @param maxSeconds   {@code limits.max-seconds}
     * @param defaultSpeed {@code flight.default-speed} pour les profils créés
     * @param nowEpoch     instant de l'import (epoch, secondes)
     * @return rapport
     * @throws SQLException erreur SQL, ou conflit avec une écriture concurrente (l'appelant annule tout)
     */
    public static ImportReport run(Connection c, List<SourceRow> rows, MergeStrategy strategy, boolean dryRun,
                                   long maxSeconds, double defaultSpeed, long nowEpoch) throws SQLException {
        Objects.requireNonNull(c, "c");
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(strategy, "strategy");
        if (maxSeconds <= 0L) {
            throw new IllegalArgumentException("maxSeconds doit être > 0");
        }
        double speed = FlightProfile.clampSpeed(defaultSpeed);

        // 1. Validation + dédoublonnage (première occurrence gagnante).
        int ignored = 0;
        Map<UUID, SourceRow.Valid> valid = new LinkedHashMap<>();
        for (SourceRow row : rows) {
            Optional<SourceRow.Valid> v = row == null ? Optional.empty() : row.validate(maxSeconds);
            if (v.isEmpty() || valid.putIfAbsent(v.get().uuid(), v.get()) != null) {
                ignored++;
            }
        }

        // 2. Profils déjà présents.
        Map<UUID, Existing> existing = readExisting(c, new ArrayList<>(valid.keySet()));

        // 3. Plan.
        List<SourceRow.Valid> inserts = new ArrayList<>();
        List<SourceRow.Valid> updates = new ArrayList<>();
        int alreadyPresent = 0;
        for (SourceRow.Valid v : valid.values()) {
            Existing e = existing.get(v.uuid());
            if (e == null) {
                inserts.add(v);
                continue;
            }
            boolean change = switch (strategy) {
                case SKIP -> false;
                case MAX -> v.seconds() > e.remaining();
                case REPLACE -> v.seconds() != e.remaining() || v.flying() != e.flying();
            };
            if (change) {
                updates.add(v);
            } else {
                alreadyPresent++;
            }
        }

        // 4. Écritures.
        if (!dryRun) {
            insertAll(c, inserts, speed, nowEpoch);
            updateAll(c, updates, existing, strategy, nowEpoch);
        }
        Set<UUID> changed = new LinkedHashSet<>();
        inserts.forEach(v -> changed.add(v.uuid()));
        updates.forEach(v -> changed.add(v.uuid()));
        return new ImportReport(rows.size(), inserts.size() + updates.size(), updates.size(), ignored,
                alreadyPresent, strategy, dryRun, changed);
    }

    private static Map<UUID, Existing> readExisting(Connection c, List<UUID> ids) throws SQLException {
        Map<UUID, Existing> out = new HashMap<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            StringBuilder sql = new StringBuilder(
                    "SELECT uuid, remaining_seconds, flight_enabled, version FROM aafly_profiles WHERE uuid IN (");
            for (int i = 0; i < chunk.size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
            }
            sql.append(')');
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < chunk.size(); i++) {
                    ps.setString(i + 1, chunk.get(i).toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID id = SourceRow.parseUuid(rs.getString(1));
                        if (id != null) {
                            out.put(id, new Existing(Math.max(0L, rs.getLong(2)), rs.getBoolean(3), rs.getLong(4)));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void insertAll(Connection c, List<SourceRow.Valid> inserts, double speed, long now)
            throws SQLException {
        if (inserts.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
            int pending = 0;
            for (SourceRow.Valid v : inserts) {
                ps.setString(1, v.uuid().toString());
                ps.setLong(2, v.seconds());
                ps.setBoolean(3, v.flying());
                ps.setLong(4, now);
                ps.setDouble(5, speed);
                ps.addBatch();
                if (++pending >= CHUNK) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
        }
    }

    private static void updateAll(Connection c, List<SourceRow.Valid> updates, Map<UUID, Existing> existing,
                                  MergeStrategy strategy, long now) throws SQLException {
        if (updates.isEmpty()) {
            return;
        }
        boolean replace = strategy == MergeStrategy.REPLACE;
        Set<UUID> seen = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(replace ? UPDATE_REPLACE_SQL : UPDATE_MAX_SQL)) {
            // Exécution ligne à ligne : le nombre de lignes modifiées de chaque UPDATE est vérifié
            // (les comptes d'un batch ne sont pas fiables sur tous les pilotes).
            for (SourceRow.Valid v : updates) {
                if (!seen.add(v.uuid())) {
                    continue;
                }
                Existing e = existing.get(v.uuid());
                int i = 1;
                ps.setLong(i++, v.seconds());
                if (replace) {
                    ps.setBoolean(i++, v.flying());
                }
                ps.setLong(i++, now);
                ps.setString(i++, v.uuid().toString());
                ps.setLong(i, e.version());
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("conflit : le profil " + v.uuid()
                            + " a été modifié pendant l'import ; rien n'a été importé, relancez la migration.");
                }
            }
        }
    }
}
