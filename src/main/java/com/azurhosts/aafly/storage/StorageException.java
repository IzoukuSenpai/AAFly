package com.azurhosts.aafly.storage;

import java.io.Serial;

/**
 * Erreur d'accès au stockage (SQL, pool, executor arrêté). Le message ne contient jamais de secret
 * (mot de passe MySQL, secret des jetons) : uniquement l'opération et la cause technique.
 */
public final class StorageException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message description lisible (en français)
     */
    public StorageException(String message) {
        super(message);
    }

    /**
     * @param message description lisible
     * @param cause   cause d'origine
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
