package com.azurhosts.aafly.storage;

/** Type de stockage (config {@code storage.type}). */
public enum StorageType {
    /** Fichier SQLite local {@code plugins/AAFly/aafly.db}. */
    SQLITE,
    /** Base MySQL/MariaDB partagée (pool HikariCP). */
    MYSQL
}
