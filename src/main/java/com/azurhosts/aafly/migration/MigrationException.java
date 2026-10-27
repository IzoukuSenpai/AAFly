package com.azurhosts.aafly.migration;

import java.io.Serial;

/**
 * Échec d'une migration avec un message destiné à l'administrateur (en français, sans secret : jamais
 * de mot de passe ni de contenu de fichier). Aucune écriture n'a eu lieu quand cette exception est levée.
 */
public final class MigrationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message message clair
     */
    public MigrationException(String message) {
        super(message);
    }

    /**
     * @param message message clair
     * @param cause   cause technique
     */
    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
