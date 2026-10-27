package com.azurhosts.aafly.config;

import java.io.Serial;

/**
 * Configuration illisible ou invalide. Au démarrage, le plugin se désactive proprement avec ce message ;
 * au rechargement, l'ancienne configuration est conservée. Le message ne contient jamais de secret.
 */
public final class ConfigException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message description lisible (en français)
     */
    public ConfigException(String message) {
        super(message);
    }

    /**
     * @param message description lisible
     * @param cause   cause d'origine
     */
    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
