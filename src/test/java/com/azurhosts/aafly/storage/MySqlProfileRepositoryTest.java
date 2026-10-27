package com.azurhosts.aafly.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azurhosts.aafly.config.Settings;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** Validation de la configuration MySQL (sans serveur : aucune connexion n'est ouverte). */
class MySqlProfileRepositoryTest {

    private static final Logger LOG = Logger.getLogger("AAFly-test");
    private static final Executor DIRECT = Runnable::run;

    private static Settings.MysqlSection mysql(String host, int port, String db) {
        return new Settings.MysqlSection(host, port, db, "aafly", "s3cr3t-P@ss", false, 6);
    }

    @Test
    void acceptsValidHosts() {
        assertDoesNotThrow(() -> new MySqlProfileRepository(mysql("localhost", 3306, "aafly"), DIRECT, LOG));
        assertDoesNotThrow(() -> new MySqlProfileRepository(mysql("db-1.azurhosts.com", 3306, "aa_fly"), DIRECT, LOG));
        assertDoesNotThrow(() -> new MySqlProfileRepository(mysql("10.0.0.5", 3307, "x"), DIRECT, LOG));
        assertDoesNotThrow(() -> new MySqlProfileRepository(mysql("[::1]", 3306, "x"), DIRECT, LOG));
    }

    @Test
    void rejectsUrlInjectionAttempts() {
        assertThrows(StorageException.class,
                () -> new MySqlProfileRepository(mysql("localhost", 3306, "aafly?allowLoadLocalInfile=true"), DIRECT, LOG));
        assertThrows(StorageException.class,
                () -> new MySqlProfileRepository(mysql("evil.com/x?autoDeserialize=true#", 3306, "aafly"), DIRECT, LOG));
        assertThrows(StorageException.class,
                () -> new MySqlProfileRepository(mysql("a,b", 3306, "aafly"), DIRECT, LOG));
        assertThrows(StorageException.class,
                () -> new MySqlProfileRepository(mysql("localhost", 0, "aafly"), DIRECT, LOG));
        assertThrows(StorageException.class,
                () -> new MySqlProfileRepository(mysql("localhost", 70_000, "aafly"), DIRECT, LOG));
        assertThrows(StorageException.class,
                () -> new MySqlProfileRepository(mysql("", 3306, "aafly"), DIRECT, LOG));
    }

    @Test
    void urlAndDescriptionNeverContainCredentials() {
        MySqlProfileRepository repo = new MySqlProfileRepository(mysql("localhost", 3306, "aafly"), DIRECT, LOG);
        assertEquals("jdbc:mysql://localhost:3306/aafly", repo.jdbcUrl());
        assertFalse(repo.describe().contains("s3cr3t"));
        assertFalse(repo.jdbcUrl().contains("s3cr3t"));
    }

    @Test
    void errorMessagesNeverContainPassword() {
        StorageException e = assertThrows(StorageException.class,
                () -> new MySqlProfileRepository(mysql("bad host", 3306, "aafly"), DIRECT, LOG));
        assertFalse(e.getMessage().contains("s3cr3t"));
    }
}
